/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gh4a.activities;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.print.PrintHelper;
import androidx.appcompat.widget.PopupMenu;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.gh4a.ApiRequestException;
import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.fragment.CommitDialogFragment;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.DownloadUtils;
import com.gh4a.utils.FileUtils;
import com.gh4a.utils.HtmlUtils;
import com.gh4a.utils.IntentUtils;
import com.gh4a.utils.StringUtils;
import com.meisolsson.githubsdk.model.ClientErrorResponse;
import com.meisolsson.githubsdk.model.Content;
import com.meisolsson.githubsdk.model.TextMatch;
import com.meisolsson.githubsdk.service.repositories.RepositoryContentService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.json.JSONException;
import org.json.JSONObject;

import io.reactivex.Single;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FileViewerActivity extends WebViewerActivity
        implements PopupMenu.OnMenuItemClickListener,
                   CommitDialogFragment.Callback,
                   com.gh4a.fragment.ConfirmationDialogFragment.Callback {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String ref, String fullPath) {
        return makeIntent(context, repoOwner, repoName, ref, fullPath, -1, -1, null);
    }

    public static Intent makeIntentWithHighlight(Context context, String repoOwner, String repoName,
            String ref, String fullPath, int highlightStart, int highlightEnd) {
        return makeIntent(context, repoOwner, repoName, ref, fullPath, highlightStart, highlightEnd,
                null);
    }

    public static Intent makeIntentWithSearchMatch(Context context, String repoOwner,
            String repoName, String ref, String fullPath, TextMatch textMatch) {
        return makeIntent(context, repoOwner, repoName, ref, fullPath, -1, -1, textMatch);
    }

    private static Intent makeIntent(Context context, String repoOwner, String repoName, String ref,
            String fullPath, int highlightStart, int highlightEnd, TextMatch textMatch) {
        return new Intent(context, FileViewerActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("path", fullPath)
                .putExtra("ref", ref)
                .putExtra("highlight_start", highlightStart)
                .putExtra("highlight_end", highlightEnd)
                .putExtra("text_match", textMatch);
    }

    private String mRepoName;
    private String mRepoOwner;
    private String mPath;
    private String mRef;
    private int mHighlightStart;
    private int mHighlightEnd;
    private TextMatch mTextMatch;
    private Content mContent;
    private int mLastTouchedLine = 0;
    private boolean mViewRawText;

    private static final int ID_LOADER_FILE = 0;
    private static final int MENU_ITEM_HISTORY = 10;
    private static final int MENU_ITEM_EDIT = 11;
    private static final int MENU_ITEM_DELETE = 12;
    private static final String TAG_COMMIT_DIALOG = "commit_dialog";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String filename = FileUtils.getFileName(mPath);
        if (FileUtils.isBinaryFormat(filename) && !FileUtils.isImage(filename)) {
            openUnsuitableFileAndFinish();
        } else {
            loadFile(false);
        }
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return FileUtils.getFileName(mPath);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mPath = extras.getString("path");
        mRef = extras.getString("ref");
        mHighlightStart = extras.getInt("highlight_start", -1);
        mHighlightEnd = extras.getInt("highlight_end", -1);
        mTextMatch = extras.getParcelable("text_match");
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return true;
    }

    @Override
    public void onRefresh() {
        setContentShown(false);
        loadFile(true);
        super.onRefresh();
    }

    @Override
    protected String generateHtml(String cssTheme, boolean addTitleHeader) {
        String base64Data = mContent.content();
        if (base64Data != null && FileUtils.isImage(mPath)) {
            String title = addTitleHeader ? getDocumentTitle() : null;
            String imageUrl = "data:" + FileUtils.getMimeTypeFor(mPath) +
                    ";base64," + base64Data;
            return highlightImage(imageUrl, cssTheme, title);
        } else if (base64Data != null && FileUtils.isMarkdown(mPath) && !mViewRawText) {
            String folderPath = FileUtils.getFolderPath(mPath);
            return generateMarkdownHtml(base64Data,
                    mRepoOwner, mRepoName, mRef, folderPath, cssTheme, addTitleHeader);
        } else {
            String data = base64Data != null ? StringUtils.fromBase64(base64Data) : "";
            findMatchingLines(data);
            return generateCodeHtml(data, mPath,
                    mHighlightStart, mHighlightEnd, cssTheme, addTitleHeader);
        }
    }

    private void findMatchingLines(String data) {
        if (mTextMatch == null) {
            return;
        }

        int[] matchingLines = StringUtils.findMatchingLines(data, mTextMatch.fragment());
        if (matchingLines != null) {
            mHighlightStart = matchingLines[0];
            mHighlightEnd = matchingLines[1];
        }
    }

    @Override
    protected String getDocumentTitle() {
        @StringRes int titleResId = TextUtils.isEmpty(mRef)
                ? R.string.file_print_document_title : R.string.file_print_document_at_ref_title;
        return getString(titleResId, FileUtils.getFileName(mPath), mRepoOwner, mRepoName, mRef);
    }

    @Override
    protected boolean handlePrintRequest() {
        if (!FileUtils.isImage(mPath)) {
            return false;
        }
        String base64Data = mContent != null ? mContent.content() : null;
        if (base64Data == null) {
            return false;
        }
        byte[] decodedData = Base64.decode(base64Data, Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedData, 0, decodedData.length);

        PrintHelper printHelper = new PrintHelper(this);
        printHelper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
        printHelper.printBitmap(getDocumentTitle(), bitmap);
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.file_viewer_menu, menu);

        boolean isMarkdown = FileUtils.isMarkdown(mPath);
        if (FileUtils.isImage(mPath) || (isMarkdown && !mViewRawText)) {
            menu.removeItem(R.id.wrap);
        }
        if (isMarkdown) {
            MenuItem viewRawItem = menu.findItem(R.id.view_raw);
            viewRawItem.setChecked(mViewRawText);
            viewRawItem.setVisible(true);
        }

        menu.add(0, MENU_ITEM_HISTORY, Menu.NONE, R.string.history)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);

        // Only show edit for text files when user is logged in
        if (com.gh4a.Gh4Application.get().isAuthorized()
                && !FileUtils.isBinaryFormat(FileUtils.getFileName(mPath))
                && !FileUtils.isImage(mPath)) {
            menu.add(0, MENU_ITEM_EDIT, Menu.NONE, R.string.edit)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);
        }

        // Show delete for all files when user is logged in
        if (com.gh4a.Gh4Application.get().isAuthorized()) {
            menu.add(0, MENU_ITEM_DELETE, Menu.NONE, R.string.delete)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Uri.Builder urlBuilder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("blob")
                .appendPath(mRef);
        for (String element: mPath.split("\\/")) {
            urlBuilder.appendPath(element);
        }
        Uri url = urlBuilder.build();

        switch (item.getItemId()) {
            case R.id.browser:
                IntentUtils.launchBrowser(this, url);
                return true;
            case R.id.download:
                DownloadUtils.enqueueDownloadWithPermissionCheck(this, buildRawFileUrl(),
                        FileUtils.getMimeTypeFor(mPath), FileUtils.getFileName(mPath), null);
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_file_subject,
                        FileUtils.getFileName(mPath), mRepoOwner + "/" + mRepoName), url);
                return true;
            case MENU_ITEM_HISTORY:
                startActivity(CommitHistoryActivity.makeIntent(this,
                        mRepoOwner, mRepoName, mRef, mPath, mContent != null ? mContent.type() : null, false));
                return true;
            case R.id.view_raw:
                mViewRawText = !mViewRawText;
                item.setChecked(mViewRawText);
                onRefresh();
                return true;
            case MENU_ITEM_EDIT:
                startActivity(FileEditActivity.makeIntent(this, mRepoOwner, mRepoName, mRef, mPath));
                return true;
            case MENU_ITEM_DELETE:
                showDeleteConfirmation();
                return true;
         }
         return super.onOptionsItemSelected(item);
     }

    @Override
    protected Intent navigateUp() {
        return RepositoryActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.share:
                if (mLastTouchedLine > 0) {
                    String subject = getString(R.string.share_line_subject, mLastTouchedLine, mPath,
                            mRepoOwner + "/" + mRepoName);
                    IntentUtils.share(this, subject, createUrl());
                }
                return true;
        }
        return false;
    }

    @Override
    protected void onLineTouched(int line, int x, int y) {
        super.onLineTouched(line, x, y);

        mLastTouchedLine = line;

        View anchor = findViewById(R.id.popup_helper);
        anchor.layout(x, y, x + 1, y + 1);
        if (!isFinishing()) {
            PopupMenu popupMenu = new PopupMenu(this, anchor);
            popupMenu.getMenuInflater().inflate(R.menu.file_line_menu, popupMenu.getMenu());
            popupMenu.show();
            popupMenu.setOnMenuItemClickListener(this);
        }
    }

    @Override
    protected boolean shouldWrapLines() {
        boolean displayingMarkdown = FileUtils.isMarkdown(mPath) && !mViewRawText;
        return !displayingMarkdown && super.shouldWrapLines();
    }

    private Uri createUrl() {
        Uri.Builder builder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("blob")
                .appendPath(mRef);
        for (String element: mPath.split("\\/")) {
            builder.appendPath(element);
        }
        builder.fragment("L" + mLastTouchedLine);
        return builder.build();
    }

    private String buildRawFileUrl() {
        return IntentUtils.createRawFileUrl(mRepoOwner, mRepoName, mRef, mPath);
    }

    private void openUnsuitableFileAndFinish() {
        Uri uri = Uri.parse(buildRawFileUrl());
        String mime = FileUtils.getMimeTypeFor(FileUtils.getFileName(mPath));
        Intent intent = IntentUtils.createViewerOrBrowserIntent(this, uri, mime);
        if (intent == null) {
            handleLoadFailure(new ActivityNotFoundException());
            findViewById(R.id.retry_button).setVisibility(View.GONE);
        } else {
            startActivity(intent);
            finish();
        }
    }

    private static String highlightImage(String imageUrl, String cssTheme, String title) {
        StringBuilder content = new StringBuilder();
        content.append("<html><head>");
        HtmlUtils.writeCssInclude(content, "markdown", cssTheme);
        content.append("</head><body>");
        if (title != null) {
            content.append("<h2>").append(title).append("</h2>");
        }
        content.append("<img src='").append(imageUrl).append("' />");
        content.append("</body></html>");
        return content.toString();
    }

    private void loadFile(boolean force) {
        RepositoryContentService service = ServiceFactory.get(RepositoryContentService.class, force);
        service.getContents(mRepoOwner, mRepoName, mPath, mRef)
                .map(ApiHelpers::throwOnFailure)
                .map(Optional::of)
                .onErrorResumeNext(error -> {
                    if (error instanceof ApiRequestException) {
                        ClientErrorResponse response = ((ApiRequestException) error).getResponse();
                        List<ClientErrorResponse.FieldError> errors =
                                response != null ? response.errors() : null;
                        if (errors != null) {
                            for (ClientErrorResponse.FieldError fe : errors) {
                                if (fe.reason() == ClientErrorResponse.FieldError.Reason.TooLarge) {
                                    openUnsuitableFileAndFinish();
                                    return Single.just(Optional.empty());
                                }
                            }
                        }
                    }
                    return Single.error(error);
                })
                .compose(makeLoaderSingle(ID_LOADER_FILE, force))
                .subscribe(result -> {
                    if (result.isPresent()) {
                        mContent = result.get();
                        // When file size is >1 and <100 MB, the GH API endpoint returns a successful response
                        // but does not include the file contents in the response payload.
                        // See https://docs.github.com/en/rest/repos/contents#get-repository-content
                        boolean fileContentIsMissing = mContent.size() > 0 && Objects.equals(mContent.content(), "");
                        if (fileContentIsMissing) {
                            openUnsuitableFileAndFinish();
                        } else {
                            onDataReady();
                            setContentEmpty(false);
                        }
                    } else {
                        setContentEmpty(true);
                        setContentShown(true);
                    }
                }, this::handleLoadFailure);
    }

    private void showDeleteConfirmation() {
        com.gh4a.fragment.ConfirmationDialogFragment.show(
                this,
                getString(R.string.file_delete_confirm),
                R.string.delete,
                android.os.Bundle.EMPTY,
                "delete_file");
    }

    @Override
    public void onCommitConfirmed(String message, String branchName) {
        doDelete(message, branchName);
    }

    @Override
    public void onConfirmed(String tag, android.os.Parcelable data) {
        if ("delete_file".equals(tag)) {
            // Load branches and show commit dialog
            loadBranchesAndShowDeleteDialog();
        }
    }

    private void loadBranchesAndShowDeleteDialog() {
        com.meisolsson.githubsdk.service.repositories.RepositoryBranchService branchService =
                ServiceFactory.getForFullPagedLists(
                        com.meisolsson.githubsdk.service.repositories.RepositoryBranchService.class, false);

        com.gh4a.utils.ApiHelpers.PageIterator
                .toSingle(page -> branchService.getBranches(mRepoOwner, mRepoName, page))
                .compose(com.gh4a.utils.RxUtils::doInBackground)
                .compose(com.gh4a.utils.RxUtils.wrapWithProgressDialog(this, R.string.loading_msg))
                .subscribe(branches -> {
                    CommitDialogFragment fragment =
                            CommitDialogFragment.newInstance(branches, mRef);
                    fragment.show(getSupportFragmentManager(), TAG_COMMIT_DIALOG);
                }, error -> handleActionFailure(
                        getString(R.string.file_delete_error), error));
    }

    private void doDelete(final String message, final String branchName) {
        Single.<String>create(emitter -> {
            try {
                String fileSha = mContent != null ? mContent.sha() : null;
                if (TextUtils.isEmpty(fileSha)) {
                    // Re-fetch SHA to ensure we have the latest
                    String getUrl = "https://api.github.com/repos/"
                            + mRepoOwner + "/" + mRepoName + "/contents/" + mPath;
                    if (!TextUtils.isEmpty(branchName)) {
                        getUrl += "?ref=" + java.net.URLEncoder.encode(branchName, "UTF-8");
                    } else {
                        getUrl += "?ref=" + java.net.URLEncoder.encode(mRef, "UTF-8");
                    }
                    Request.Builder getRequestBuilder = new Request.Builder()
                            .url(getUrl)
                            .get()
                            .addHeader("Accept", "application/vnd.github.v3+json");
                    String token = Gh4Application.get().getAuthToken();
                    if (token != null) {
                        getRequestBuilder.addHeader("Authorization", "Token " + token);
                    }
                    Response getResponse = ServiceFactory.getHttpClientBuilder()
                            .build().newCall(getRequestBuilder.build()).execute();
                    if (getResponse.isSuccessful() && getResponse.body() != null) {
                        JSONObject getContentJson = new JSONObject(getResponse.body().string());
                        if (getContentJson.has("sha")) {
                            fileSha = getContentJson.getString("sha");
                        }
                    }
                }

                if (TextUtils.isEmpty(fileSha)) {
                    emitter.onError(new RuntimeException(getString(R.string.file_delete_error)
                            + "\n无法获取文件SHA，可能已被删除或无权限。"));
                    return;
                }

                // Build API URL for deletion
                StringBuilder encodedPath = new StringBuilder();
                for (String segment : mPath.split("/", -1)) {
                    if (encodedPath.length() > 0) encodedPath.append("/");
                    encodedPath.append(java.net.URLEncoder.encode(segment, "UTF-8"));
                }
                String url = "https://api.github.com/repos/"
                        + mRepoOwner + "/" + mRepoName + "/contents/" + encodedPath.toString();

                String targetBranch = !TextUtils.isEmpty(branchName) ? branchName : mRef;

                // Build DELETE request body: message + sha + branch
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("message", message);
                jsonBody.put("sha", fileSha);
                jsonBody.put("branch", targetBranch);

                RequestBody body = RequestBody.create(
                        MediaType.parse("application/json"), jsonBody.toString());

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .delete(body)
                        .addHeader("Accept", "application/vnd.github.v3+json");
                String token = Gh4Application.get().getAuthToken();
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "Token " + token);
                }

                Response response = ServiceFactory.getHttpClientBuilder()
                        .build().newCall(requestBuilder.build()).execute();

                if (response.isSuccessful()) {
                    emitter.onSuccess("DELETED");
                } else {
                    int code = response.code();
                    String errorMsg = response.body() != null ? response.body().string() : "";
                    if (code == 401 || code == 403) {
                        emitter.onError(new RuntimeException(getString(R.string.file_no_write_permission)));
                    } else if (code == 404) {
                        emitter.onError(new RuntimeException(getString(R.string.file_delete_error)
                                + "\n文件未找到，可能已被删除。"));
                    } else if (code == 409) {
                        emitter.onError(new RuntimeException(getString(R.string.file_conflict_error)));
                    } else {
                        try {
                            JSONObject errJson = new JSONObject(errorMsg);
                            String ghMessage = errJson.optString("message", "");
                            emitter.onError(new RuntimeException(
                                    getString(R.string.file_delete_error) + " (HTTP " + code + ")"
                                            + (!ghMessage.isEmpty() ? ": " + ghMessage : "")));
                        } catch (JSONException e) {
                            emitter.onError(new RuntimeException(
                                    getString(R.string.file_delete_error) + " (HTTP " + code + ")"));
                        }
                    }
                }
            } catch (Exception e) {
                emitter.onError(e);
            }
        })
                .compose(com.gh4a.utils.RxUtils::doInBackground)
                .compose(com.gh4a.utils.RxUtils.wrapForBackgroundTask(this,
                        R.string.deleting_msg, getString(R.string.file_delete_error)))
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(result -> finish(),
                           error -> handleActionFailure(
                                   error.getMessage() != null ? error.getMessage()
                                           : getString(R.string.file_delete_error), error));
    }
}
