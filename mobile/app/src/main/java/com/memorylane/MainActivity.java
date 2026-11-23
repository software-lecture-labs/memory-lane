package com.memorylane;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.memorylane.core.di.AppContainer;
import com.memorylane.core.navigation.SessionNavigator;
import com.memorylane.databinding.ActivityMainBinding;

/** Single activity; every tab is a fragment destination in res/navigation/nav_graph.xml. */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 35 is edge-to-edge on Android 15 anyway; enabling it everywhere keeps
        // older versions consistent. The bottom bar pads itself for the gesture area.
        EdgeToEdge.enable(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (navHost == null) {
            throw new IllegalStateException("activity_main.xml must contain the nav_host fragment");
        }
        NavController navController = navHost.getNavController();
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHost, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            boolean authScreen = navController.getCurrentDestination() != null
                    && isAuthScreen(navController.getCurrentDestination().getId());
            int bottom = authScreen
                    ? Math.max(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom, bars.bottom)
                    : 0;
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        NavigationUI.setupWithNavController(binding.bottomNav, navController);
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            binding.bottomNav.setVisibility(isAuthScreen(destination.getId()) ? View.GONE : View.VISIBLE);
            ViewCompat.requestApplyInsets(binding.navHost);
        });
        // Without a session only public screens are shown; signing out clears the whole history.
        SessionNavigator.bind(this, navController, AppContainer.from(this).getSessionStore());
    }

    /** Login and register hide the bottom bar and pad for the keyboard so the form stays reachable. */
    private static boolean isAuthScreen(int destinationId) {
        return destinationId == R.id.loginFragment || destinationId == R.id.registerFragment;
    }
}
