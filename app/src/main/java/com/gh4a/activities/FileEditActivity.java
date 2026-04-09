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

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.gh4a.BaseActivity;
import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.fragment.ConfirmationDialogFragment;
import com.gh4a.fragment.CommitDialogFragment;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.FileUtils;
import com.gh4a.utils.RxUtils;
import com.gh4a.utils.StringUtils;
import com.gh4a.widget.CommentEditor;
import com.meisolsson.githubsdk.model.Branch;
import com.meisolsson.githubsdk.model.Content;
import com.meisolsson.githubsdk.service.repositories.RepositoryBranchService;
import com.meisolsson.githubsdk.service.repositories.RepositoryContentService;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.json.JSONException;
import org.json.JSONObject;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FileEditActivity extends BaseActivity implements
        ConfirmationDialogFragment.Callback,
        CommitDialogFragment.Callback {

    public static Intent makeIntent(Context context, String repoOwner,
            String repoName, String ref, String path) {
        return new Intent(context, FileEditActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("path", path)
                .putExtra("ref", ref);
    }

    private static final int ID_LOADER_FILE = 0;
    private static final String STATE_ORIGINAL_CONTENT = "original_content";
    private static final String STATE_EDITED_CONTENT = "edited_content";
    private static final String STATE_FILE_SHA = "file_sha";
    private static final String STATE_BRANCHES = "branches";
    private static final String TAG_UNSAVED_CHANGES = "unsaved_changes";
    private static final String TAG_COMMIT_DIALOG = "commit_dialog";

    private String mRepoOwner;
    private String mRepoName;
    private String mPath;
    private String mRef;
    private String mFileSha;
    private String mOriginalContent;
    private CommentEditor mEditor;
    private MenuItem mSaveMenuItem;
    private boolean mHasUnsavedChanges = false;
    private List<Branch> mBranches;

    private final ActivityResultLauncher<String> mImagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                handleSelectedImage(uri);
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check login status first
        if (!Gh4Application.get().isAuthorized()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_file_edit);
        mEditor = findViewById(R.id.editor);
        setupEditor();

        loadFile(false);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mPath = extras.getString("path");
        mRef = extras.getString("ref");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ORIGINAL_CONTENT, mOriginalContent);
        outState.putString(STATE_EDITED_CONTENT,
                mEditor.getText() != null ? mEditor.getText().toString() : null);
        outState.putString(STATE_FILE_SHA, mFileSha);
        if (mBranches != null) {
            outState.putParcelableArrayList(STATE_BRANCHES, new ArrayList<>(mBranches));
        }
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.file_edit_title, FileUtils.getFileName(mPath));
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return true;
    }

    @Override
    public void onRefresh() {
        setContentShown(false);
        loadFile(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.file_edit_menu, menu);
        mSaveMenuItem = menu.findItem(R.id.save);
        updateSaveButtonState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.save) {
            saveFile();
            return true;
        }
        if (item.getItemId() == R.id.insert_image) {
            mImagePickerLauncher.launch("image/*");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mHasUnsavedChanges) {
            showUnsavedChangesConfirmation();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfirmed(String tag, @Nullable android.os.Parcelable data) {
        if (TAG_UNSAVED_CHANGES.equals(tag)) {
            super.onBackPressed();
        }
    }

    @Override
    public void onCommitConfirmed(String message, String branchName) {
        if (TextUtils.isEmpty(message)) {
            // Show error - empty commit message
            return;
        }
        doCommit(message, branchName);
    }

    private void setupEditor() {
        mEditor.setHint(getString(R.string.file_edit_hint));
        mEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                boolean hasChanges = mOriginalContent != null
                        && !s.toString().equals(mOriginalContent);
                if (hasChanges != mHasUnsavedChanges) {
                    mHasUnsavedChanges = hasChanges;
                    updateSaveButtonState();
                }
            }
        });
    }

    private void updateSaveButtonState() {
        if (mSaveMenuItem != null) {
            mSaveMenuItem.setEnabled(mHasUnsavedChanges && mOriginalContent != null);
        }
    }

    private void loadFile(boolean force) {
        RepositoryContentService service =
                ServiceFactory.get(RepositoryContentService.class, force);
        service.getContents(mRepoOwner, mRepoName, mPath, mRef)
                .map(ApiHelpers::throwOnFailure)
                .map(Optional::of)
                .onErrorResumeNext(error -> Single.error(error))
                .compose(makeLoaderSingle(ID_LOADER_FILE, force))
                .subscribe(result -> {
                    Content content = result.orElse(null);
                    if (content == null || TextUtils.isEmpty(content.content())) {
                        handleLoadFailure(new RuntimeException(
                                getString(R.string.file_loading_error)));
                        return;
                    }
                    mFileSha = content.sha();

                    String rawContent = StringUtils.fromBase64(content.content());
                    mOriginalContent = rawContent;
                    mEditor.setText(rawContent);
                    mHasUnsavedChanges = false;
                    updateSaveButtonState();

                    setContentShown(true);
                }, this::handleLoadFailure);
    }

    private void saveFile() {
        if (TextUtils.isEmpty(mEditor.getText().toString().trim())) {
            // Don't allow empty files
            handleActionFailure(getString(R.string.file_empty_content_error),
                    new IllegalArgumentException());
            return;
        }

        if (mBranches == null) {
            loadBranchesAndShowCommitDialog();
        } else {
            showCommitDialog();
        }
    }

    private void loadBranchesAndShowCommitDialog() {
        RepositoryBranchService branchService =
                ServiceFactory.getForFullPagedLists(RepositoryBranchService.class, false);

        ApiHelpers.PageIterator
                .toSingle(page -> branchService.getBranches(mRepoOwner, mRepoName, page))
                .compose(RxUtils::doInBackground)
                .compose(RxUtils.wrapWithProgressDialog(this, R.string.loading_msg))
                .subscribe(branches -> {
                    mBranches = branches;
                    showCommitDialog();
                }, error -> handleActionFailure(
                        getString(R.string.file_saving_error), error));
    }

    private void showCommitDialog() {
        CommitDialogFragment fragment =
                CommitDialogFragment.newInstance(mBranches, mRef);
        fragment.show(getSupportFragmentManager(), TAG_COMMIT_DIALOG);
    }

    private void doCommit(String message, String branchName) {
        String newContentBase64 = StringUtils.toBase64(mEditor.getText().toString());
        String targetBranch = branchName != null ? branchName : mRef;

        Single.<String>create(emitter -> {
            try {
                // Encode each path segment separately to preserve '/' separators
                String[] pathSegments = mPath.split("/", -1);
                StringBuilder encodedPath = new StringBuilder();
                for (int i = 0; i < pathSegments.length; i++) {
                    if (i > 0) {
                        encodedPath.append("/");
                    }
                    encodedPath.append(URLEncoder.encode(pathSegments[i], StandardCharsets.UTF_8));
                }

                String baseUrl = "https://api.github.com/repos/"
                        + mRepoOwner + "/" + mRepoName;
                String url = baseUrl + "/contents/" + encodedPath.toString();

                String token = Gh4Application.get().getAuthToken();

                // Pre-flight: verify token has push permission for this repo.
                // GitHub's default Fine-grained tokens often lack contents write access,
                // which causes confusing 404 errors instead of 403.
                Request.Builder permReqBuilder = new Request.Builder()
                        .url(baseUrl)
                        .get()
                        .addHeader("Accept", "application/vnd.github.v3+json");
                if (token != null) {
                    permReqBuilder.addHeader("Authorization", "Token " + token);
                }

                Response permResponse = ServiceFactory.getHttpClientBuilder()
                        .build()
                        .newCall(permReqBuilder.build())
                        .execute();
                String permBody = permResponse.body() != null ? permResponse.body().string() : "";

                boolean canPush = false;
                if (permResponse.isSuccessful()) {
                    try {
                        JSONObject permJson = new JSONObject(permBody);
                        JSONObject permissions = permJson.optJSONObject("permissions");
                        canPush = permissions != null && permissions.optBoolean("push", false);
                    } catch (JSONException e) {
                        android.util.Log.w("FileEdit", "Failed to parse repo permissions", e);
                    }
                }

                if (!canPush) {
                    emitter.onError(new RuntimeException(
                            getString(R.string.file_no_write_permission)
                                    + "\n\n请确认您的 Token 类型为 Classic 并包含 repo 权限。\n"
                                    + "GitHub 默认生成的 Fine-grained Token 不支持文件写入。"));
                    return;
                }

                // Re-fetch the current file SHA to ensure it hasn't changed since loading.
                // This prevents SHA mismatch if the file was modified between load and save.
                String getUrl = url + "?ref=" + URLEncoder.encode(targetBranch, StandardCharsets.UTF_8);
                Request.Builder getRequestBuilder = new Request.Builder()
                        .url(getUrl)
                        .get()
                        .addHeader("Accept", "application/vnd.github.v3+json");
                if (token != null) {
                    getRequestBuilder.addHeader("Authorization", "Token " + token);
                }

                Response getResponse = ServiceFactory.getHttpClientBuilder()
                        .build()
                        .newCall(getRequestBuilder.build())
                        .execute();

                String currentSha = null;
                if (getResponse.isSuccessful() && getResponse.body() != null) {
                    try {
                        JSONObject getContentJson = new JSONObject(getResponse.body().string());
                        if (getContentJson.has("sha")) {
                            currentSha = getContentJson.getString("sha");
                        }
                    } catch (JSONException e) {
                        android.util.Log.w("FileEdit", "Failed to parse file SHA response", e);
                    }
                }

                if (TextUtils.isEmpty(currentSha)) {
                    currentSha = mFileSha;
                } else if (!currentSha.equals(mFileSha)) {
                    android.util.Log.w("FileEdit",
                            "SHA differs from cached value — file may have been updated externally");
                }

                // PUT request to update file contents via GitHub API.
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("message", message);
                jsonBody.put("content", newContentBase64);
                jsonBody.put("sha", currentSha);
                jsonBody.put("branch", targetBranch);

                RequestBody body = RequestBody.create(
                        MediaType.parse("application/json"), jsonBody.toString());

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .put(body)
                        .addHeader("Accept", "application/vnd.github.v3+json");
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "Token " + token);
                }

                Response response = ServiceFactory.getHttpClientBuilder()
                        .build()
                        .newCall(requestBuilder.build())
                        .execute();

                int code = response.code();
                if (response.isSuccessful()) {
                    emitter.onSuccess("OK");
                } else if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                        || code == HttpURLConnection.HTTP_FORBIDDEN) {
                    emitter.onError(new RuntimeException(
                            getString(R.string.file_no_write_permission)));
                } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    emitter.onError(new RuntimeException(
                            getString(R.string.file_saving_error)
                                    + "\n文件未找到，可能是 Token 无写入权限或 SHA 已过期。"));
                } else if (code == HttpURLConnection.HTTP_CONFLICT) {
                    emitter.onError(new RuntimeException(
                            getString(R.string.file_conflict_error)));
                } else {
                    String errorMsg = getString(R.string.file_saving_error)
                            + " (HTTP " + code + ")";
                    emitter.onError(new RuntimeException(errorMsg));
                }
            } catch (Exception e) {
                emitter.onError(e);
            }
        })
                .compose(RxUtils::doInBackground)
                .compose(RxUtils.wrapForBackgroundTask(this,
                        R.string.saving_msg, getString(R.string.file_saving_error)))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    mHasUnsavedChanges = false;
                    setResult(RESULT_OK);
                    finish();
                }, error -> handleActionFailure(
                        error.getMessage() != null ? error.getMessage()
                                : getString(R.string.file_saving_error), error));
    }

    private void handleSelectedImage(Uri uri) {
        Single.<String>create(emitter -> {
            try {
                // Limit: compressed output should stay under 500KB base64 (~375KB raw)
                long maxOutputBytes = 500L * 1024;

                // Read and decode bitmap
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                android.graphics.BitmapFactory.Options decodeOpts = new android.graphics.BitmapFactory.Options();
                decodeOpts.inJustDecodeBounds = true;
                byte[] headerBytes = new byte[32 * 1024];
                int headerRead = is.read(headerBytes);
                is.close();

                if (headerRead <= 0) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }
                android.graphics.BitmapFactory.decodeByteArray(headerBytes, 0, headerRead, decodeOpts);

                int originalWidth = decodeOpts.outWidth;
                int originalHeight = decodeOpts.outHeight;

                if (originalWidth <= 0 || originalHeight <= 0) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                // Calculate sample size to downscale: cap at 800px on longest edge
                int maxDimension = 800;
                int sampleSize = 1;
                while (originalWidth / (sampleSize * 2) > maxDimension
                        || originalHeight / (sampleSize * 2) > maxDimension) {
                    sampleSize *= 2;
                }

                // Decode with sampling
                android.graphics.BitmapFactory.Options finalDecodeOpts = new android.graphics.BitmapFactory.Options();
                finalDecodeOpts.inSampleSize = sampleSize;
                java.io.InputStream fullStream = getContentResolver().openInputStream(uri);
                if (fullStream == null) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(fullStream, null, finalDecodeOpts);
                fullStream.close();

                if (bitmap == null) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                // Further scale if still too large after sampling
                int scaledWidth = bitmap.getWidth();
                int scaledHeight = bitmap.getHeight();
                if (scaledWidth > maxDimension || scaledHeight > maxDimension) {
                    float scale = (float) maxDimension / Math.max(scaledWidth, scaledHeight);
                    int targetW = Math.round(scaledWidth * scale);
                    int targetH = Math.round(scaledHeight * scale);
                    android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                            bitmap, targetW, targetH, true);
                    bitmap.recycle();
                    bitmap = scaled;
                }

                // Compress as JPEG with adaptive quality to meet size limit
                byte[] compressedBytes = null;
                int quality = 85;
                while (quality >= 30) {
                    java.io.ByteArrayOutputStream jpegOut = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, jpegOut);
                    compressedBytes = jpegOut.toByteArray();
                    if (compressedBytes.length <= maxOutputBytes || quality <= 30) {
                        break;
                    }
                    quality -= 15;
                }
                bitmap.recycle();

                if (compressedBytes.length > maxOutputBytes) {
                    emitter.onError(new RuntimeException(getString(R.string.image_too_large)));
                    return;
                }

                String base64 = Base64.getEncoder().encodeToString(compressedBytes);

                // Determine MIME type
                String mimeType = "image/jpeg";

                // Get display name from URI
                String displayName = "image";
                Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (colIndex >= 0) {
                        displayName = cursor.getString(colIndex);
                        int dotIndex = displayName.lastIndexOf('.');
                        if (dotIndex > 0) {
                            displayName = displayName.substring(0, dotIndex);
                        }
                    }
                    cursor.close();
                }

                // Build markdown image tag with data URI
                String imageMarkdown = "![" + displayName + "](data:" + mimeType + ";base64," + base64 + ")";
                emitter.onSuccess(imageMarkdown);
            } catch (Exception e) {
                emitter.onError(e);
            }
        })
                .compose(RxUtils::doInBackground)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(imageMarkdown -> {
                    mEditor.addText(imageMarkdown);
                }, error -> handleActionFailure(
                        error.getMessage() != null ? error.getMessage()
                                : getString(R.string.image_insert_error), error));
    }

    private void showUnsavedChangesConfirmation() {
        ConfirmationDialogFragment.show(this, getString(R.string.file_unsaved_changes_warning),
                R.string.ok, false, null, TAG_UNSAVED_CHANGES);
    }
}
