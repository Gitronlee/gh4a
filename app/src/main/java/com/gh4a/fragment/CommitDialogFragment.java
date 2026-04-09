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
package com.gh4a.fragment;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.gh4a.R;
import com.meisolsson.githubsdk.model.Branch;

import java.util.ArrayList;
import java.util.List;

public class CommitDialogFragment extends DialogFragment {
    public interface Callback {
        void onCommitConfirmed(String message, String branchName);
    }

    private static final String ARG_BRANCHES = "branches";
    private static final String ARG_SELECTED_REF = "selected_ref";

    private Callback mCallback;
    private EditText mMessageEditText;
    private int mSelectedBranchPosition = -1;

    public static CommitDialogFragment newInstance(List<Branch> branches,
            String selectedRef) {
        Bundle args = new Bundle();
        args.putParcelableArrayList(ARG_BRANCHES, new ArrayList<>(branches));
        args.putString(ARG_SELECTED_REF, selectedRef);
        CommitDialogFragment fragment = new CommitDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof Callback) {
            mCallback = (Callback) getParentFragment();
        } else if (context instanceof Callback) {
            mCallback = (Callback) context;
        } else {
            throw new ClassCastException("No callback provided");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        List<Branch> branches = args.getParcelableArrayList(ARG_BRANCHES);
        String selectedRef = args.getString(ARG_SELECTED_REF);

        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_commit, null);

        mMessageEditText = view.findViewById(R.id.et_commit_message);

        List<String> branchNames = new ArrayList<>();
        for (Branch branch : branches) {
            branchNames.add(branch.name());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, branchNames);

        // Find selected position
        for (int i = 0; i < branchNames.size(); i++) {
            if (branchNames.get(i).equals(selectedRef)) {
                mSelectedBranchPosition = i;
                break;
            }
        }
        if (mSelectedBranchPosition < 0 && !branchNames.isEmpty()) {
            mSelectedBranchPosition = 0;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.file_commit_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.commit, (dialog, which) -> {
                    String message = mMessageEditText.getText().toString().trim();
                    String branchName = branchNames.get(mSelectedBranchPosition);
                    mCallback.onCommitConfirmed(message, branchName);
                })
                .setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();

        // Setup spinner after dialog is created so we can access it
        dialog.setOnShowListener(d -> {
            android.widget.Spinner branchSpinner = view.findViewById(R.id.spinner_branch);
            branchSpinner.setAdapter(adapter);
            branchSpinner.setSelection(mSelectedBranchPosition);
            branchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                        int position, long id) {
                    mSelectedBranchPosition = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        });

        return dialog;
    }
}
