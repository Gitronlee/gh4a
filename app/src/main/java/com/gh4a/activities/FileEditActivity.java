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
import android.graphics.Bitmap;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    /** Images inserted by user: key=relative path in repo, value=temp local File */
    private final java.util.LinkedHashMap<String, File> mInsertedImages = new java.util.LinkedHashMap<>();

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

        // Build list of all files to commit: main text + inserted images
        List<FileToCommit> filesToCommit = new ArrayList<>();

        // Main text file
        filesToCommit.add(new FileToCommit(mPath, newContentBase64, null));

        // Inserted image files (base64 encode each)
        if (!mInsertedImages.isEmpty()) {
            for (java.util.Map.Entry<String, File> entry : mInsertedImages.entrySet()) {
                String imagePath = entry.getKey();
                File imageFile = entry.getValue();
                try {
                    InputStream imgIs = new java.io.FileInputStream(imageFile);
                    byte[] imgBytes = new byte[(int) imageFile.length()];
                    int totalRead = 0;
                    while (totalRead < imgBytes.length) {
                        int read = imgIs.read(imgBytes, totalRead, imgBytes.length - totalRead);
                        if (read < 0) break;
                        totalRead += read;
                    }
                    imgIs.close();
                    String imgBase64 = Base64.getEncoder().encodeToString(imgBytes);
                    filesToCommit.add(new FileToCommit(imagePath, imgBase64, null));
                } catch (Exception e) {
                    android.util.Log.e("FileEdit", "Failed to read image: " + imagePath, e);
                }
            }
        }

        final List<FileToCommit> finalFiles = filesToCommit;

        Single.<String>create(emitter -> {
            try {
                // Encode each path segment separately to preserve '/' separators
                String[] pathSegments = mPath.split("/", -1);
                StringBuilder encodedPath = new StringBuilder();
                for (int i = 0; i < pathSegments.length; i++) {
                    if (i > 0) encodedPath.append("/");
                    encodedPath.append(URLEncoder.encode(pathSegments[i], StandardCharsets.UTF_8));
                }

                String baseUrl = "https://api.github.com/repos/"
                        + mRepoOwner + "/" + mRepoName;
                String url = baseUrl + "/contents/" + encodedPath.toString();

                String token = Gh4Application.get().getAuthToken();

                // Pre-flight: verify token has push permission for this repo.
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

                // Re-fetch the current SHA of the main file
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

                // Commit each file via GitHub API
                int successCount = 0;
                for (int i = 0; i < finalFiles.size(); i++) {
                    FileToCommit ftc = finalFiles.get(i);

                    // Encode this file's path
                    String[] fSegments = ftc.path.split("/", -1);
                    StringBuilder fEncodedPath = new StringBuilder();
                    for (int j = 0; j < fSegments.length; j++) {
                        if (j > 0) fEncodedPath.append("/");
                        fEncodedPath.append(URLEncoder.encode(fSegments[j], StandardCharsets.UTF_8));
                    }
                    String fUrl = baseUrl + "/contents/" + fEncodedPath.toString();

                    // For image files that are new (not existing in repo), we need
                    // their SHA only if they already exist. Try to fetch.
                    String fileSha = ftc.sha != null ? ftc.sha : null;
                    if (fileSha == null && !ftc.path.equals(mPath)) {
                        // New image: check if it exists
                        String imgGetUrl = fUrl + "?ref=" + URLEncoder.encode(targetBranch, StandardCharsets.UTF_8);
                        Request.Builder imgGetBuilder = new Request.Builder()
                                .url(imgGetUrl)
                                .get()
                                .addHeader("Accept", "application/vnd.github.v3+json");
                        if (token != null) {
                            imgGetBuilder.addHeader("Authorization", "Token " + token);
                        }
                        Response imgGetResp = ServiceFactory.getHttpClientBuilder()
                                .build()
                                .newCall(imgGetBuilder.build())
                                .execute();
                        if (imgGetResp.isSuccessful() && imgGetResp.body() != null) {
                            try {
                                JSONObject imgJson = new JSONObject(imgGetResp.body().string());
                                if (imgJson.has("sha")) {
                                    fileSha = imgJson.getString("sha");
                                }
                            } catch (JSONException ignored) { }
                        }
                        // For the first file (main text), use fetched sha
                        if (ftc.path.equals(mPath)) {
                            fileSha = currentSha;
                        }
                    }
                    if (ftc.path.equals(mPath)) {
                        fileSha = currentSha;
                    }

                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("message", message);
                    jsonBody.put("content", ftc.content);
                    if (fileSha != null) {
                        jsonBody.put("sha", fileSha);
                    }
                    jsonBody.put("branch", targetBranch);

                    RequestBody body = RequestBody.create(
                            MediaType.parse("application/json"), jsonBody.toString());

                    Request.Builder requestBuilder = new Request.Builder()
                            .url(fUrl)
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
                        successCount++;
                    } else {
                        android.util.Log.e("FileEdit",
                                "Failed to commit " + ftc.path + ": HTTP " + code);
                        // Don't fail entire operation for non-critical image errors
                        if (ftc.path.equals(mPath)) {
                            if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                                    || code == HttpURLConnection.HTTP_FORBIDDEN) {
                                emitter.onError(new RuntimeException(
                                        getString(R.string.file_no_write_permission)));
                                return;
                            } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                                emitter.onError(new RuntimeException(
                                        getString(R.string.file_saving_error)
                                                + "\n文件未找到，可能是 Token 无写入权限或 SHA 已过期。"));
                                return;
                            } else if (code == HttpURLConnection.HTTP_CONFLICT) {
                                emitter.onError(new RuntimeException(
                                        getString(R.string.file_conflict_error)));
                                return;
                            } else {
                                emitter.onError(new RuntimeException(
                                        getString(R.string.file_saving_error) + " (HTTP " + code + ")"));
                                return;
                            }
                        }
                    }
                }

                if (successCount > 0) {
                    emitter.onSuccess("OK (" + successCount + " files)");
                } else {
                    emitter.onError(new RuntimeException(getString(R.string.file_saving_error)));
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
                // Size limit for source image
                long maxInputBytes = 5L * 1024 * 1024;

                // Get display name from URI
                String displayName = "image";
                Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null);
                long fileSize = 0;
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx);
                    cursor.close();
                }

                if (fileSize > maxInputBytes) {
                    emitter.onError(new RuntimeException(getString(R.string.image_too_large)));
                    return;
                }

                // Determine target directory: same directory as the file being edited,
                // with an "assets" subfolder to avoid cluttering the source tree.
                String fileDir = mPath;
                int lastSlash = fileDir.lastIndexOf('/');
                String basePath = lastSlash >= 0 ? fileDir.substring(0, lastSlash + 1) : "";
                String assetsDir = basePath + "assets/";

                // Build a safe filename (lowercase, no spaces, unique)
                String safeName = displayName.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
                if (!safeName.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) {
                    safeName = safeName + ".png";
                }
                String repoImagePath = assetsDir + safeName;

                // Ensure uniqueness by appending counter if needed
                String baseName = safeName;
                String baseExt = "";
                int dotIdx = baseName.lastIndexOf('.');
                if (dotIdx > 0) {
                    baseExt = baseName.substring(dotIdx);
                    baseName = baseName.substring(0, dotIdx);
                }
                int counter = 1;
                while (mInsertedImages.containsKey(repoImagePath)) {
                    repoImagePath = assetsDir + baseName + "_" + counter + baseExt;
                    counter++;
                }

                // Read source bytes with compression pipeline
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                byte[] headerBytes = new byte[32 * 1024];
                int headerRead = is.read(headerBytes);
                is.close();

                if (headerRead <= 0) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                decodeOpts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(headerBytes, 0, headerRead, decodeOpts);

                int originalWidth = decodeOpts.outWidth;
                int originalHeight = decodeOpts.outHeight;

                if (originalWidth <= 0 || originalHeight <= 0) {
                    // Not a decodable image (e.g., SVG); copy raw bytes directly
                    InputStream rawStream = getContentResolver().openInputStream(uri);
                    File tempFile = createTempImageFile(safeName);
                    copyToFile(rawStream, tempFile);
                    rawStream.close();
                    mInsertedImages.put(repoImagePath, tempFile);
                    String markdown = "![" + displayName + "](" + repoImagePath + ")";
                    emitter.onSuccess(markdown);
                    return;
                }

                // Stage 1: inSampleSize downsample
                int maxDimension = 1600; // Higher than before since we're saving as file now
                int sampleSize = 1;
                while (originalWidth / (sampleSize * 2) > maxDimension
                        || originalHeight / (sampleSize * 2) > maxDimension) {
                    sampleSize *= 2;
                }

                BitmapFactory.Options finalOpts = new BitmapFactory.Options();
                finalOpts.inSampleSize = sampleSize;
                InputStream fullStream = getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(fullStream, null, finalOpts);
                fullStream.close();

                if (bitmap == null) {
                    emitter.onError(new RuntimeException(getString(R.string.image_insert_error)));
                    return;
                }

                // Stage 2: Scale down if still too large
                int w = bitmap.getWidth(), h = bitmap.getHeight();
                if (w > maxDimension || h > maxDimension) {
                    float scale = (float) maxDimension / Math.max(w, h);
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                            Math.round(w * scale), Math.round(h * scale), true);
                    bitmap.recycle();
                    bitmap = scaled;
                }

                // Stage 3: Compress and write to temp file
                File tempFile = createTempImageFile(safeName);
                long maxOutputBytes = 1024L * 1024; // 1MB limit for file-based approach
                boolean written = false;

                // Try PNG first for quality
                ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                byte[] pngBytes = pngOut.toByteArray();

                if (pngBytes.length <= maxOutputBytes) {
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    fos.write(pngBytes);
                    fos.close();
                    // Fix extension to .png if needed
                    if (!repoImagePath.endsWith(".png")) {
                        repoImagePath = repoImagePath.replaceAll("\\.[^.]+$", "") + ".png";
                        mInsertedImages.remove(repoImagePath);
                    }
                    written = true;
                } else {
                    // Fall back to JPEG with adaptive quality
                    int quality = 90;
                    while (quality >= 50) {
                        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut);
                        byte[] jpegBytes = jpegOut.toByteArray();
                        if (jpegBytes.length <= maxOutputBytes) {
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            fos.write(jpegBytes);
                            fos.close();
                            // Fix extension to .jpg
                            repoImagePath = repoImagePath.replaceAll("\\.[^.]+$", "") + ".jpg";
                            written = true;
                            break;
                        }
                        quality -= 10;
                    }
                }
                bitmap.recycle();

                if (!written) {
                    tempFile.delete();
                    emitter.onError(new RuntimeException(
                            getString(R.string.image_too_large)));
                    return;
                }

                mInsertedImages.put(repoImagePath, tempFile);
                String markdown = "![" + displayName + "](" + repoImagePath + ")";
                emitter.onSuccess(markdown);

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

    /** Create a temporary file for storing an inserted image */
    private File createTempImageFile(String name) throws Exception {
        File cacheDir = new File(getCacheDir(), "edit_images");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        // Ensure unique filename in local filesystem
        String baseName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File file = new File(cacheDir, System.currentTimeMillis() + "_" + baseName);
        int counter = 1;
        while (file.exists()) {
            file = new File(cacheDir, System.currentTimeMillis() + "_" + counter + "_" + baseName);
            counter++;
        }
        return file;
    }

    /** Copy InputStream contents to a File */
    private void copyToFile(InputStream is, File file) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        is.close();
    }

    private void showUnsavedChangesConfirmation() {
        ConfirmationDialogFragment.show(this, getString(R.string.file_unsaved_changes_warning),
                R.string.ok, false, null, TAG_UNSAVED_CHANGES);
    }

    /** Holds data for a single file to commit to GitHub */
    private static class FileToCommit {
        final String path;      // Repo-relative path
        final String content;   // Base64-encoded content
        final String sha;       // Known SHA (null for new files)

        FileToCommit(String path, String content, String sha) {
            this.path = path;
            this.content = content;
            this.sha = sha;
        }
    }
}
