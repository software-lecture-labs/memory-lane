package com.memorylane.feature.profile.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.memorylane.R;

/** Placeholder tab; account and session screens belong to their own cards. */
public class ProfileFragment extends Fragment {
    public ProfileFragment() {
        super(R.layout.fragment_profile);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.preview_login_button).setOnClickListener(button ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_profileFragment_to_loginFragment));
    }
}
