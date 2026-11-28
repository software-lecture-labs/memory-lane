package com.memorylane.feature.memories.ui;

import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.memorylane.BuildConfig;
import com.memorylane.R;
import com.memorylane.databinding.FragmentMemoryLocationPickerBinding;
import com.memorylane.feature.memories.data.MemoryApi;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.util.Collections;

/**
 * Map picker for a memory's location (ML-15): a tap places the point, "Bu konumu kullan" or
 * "Konumu kaldır" hands {@link PickedLocation#RESULT} to the form that opened it. Needs no location
 * permission. If the map cannot load, going back leaves the form exactly as it was.
 */
public class MemoryLocationPickerFragment extends Fragment {
    private static final String ARG_LATITUDE = "latitude";
    private static final String ARG_LONGITUDE = "longitude";
    private static final String SOURCE = "picked";
    private static final String LAYER = "picked-pin";
    private static final float PIN_RADIUS = 12f;
    private static final float PIN_STROKE = 3f;
    private static final double PICKED_ZOOM = 12;
    /** Without a location the map opens over Türkiye. */
    private static final LatLng START = new LatLng(39.0, 35.0);
    private static final double START_ZOOM = 4.5;

    private FragmentMemoryLocationPickerBinding binding;
    private MemoryLocationPickerViewModel viewModel;
    @Nullable private MapLibreMap map;
    @Nullable private Style style;
    private final MapLibreMap.OnMapClickListener tapListener = this::onMapTap;

    /** Arguments for opening the picker on the form's current location, or none. */
    static Bundle arguments(@Nullable MemoryApi.Location current) {
        Bundle arguments = new Bundle();
        if (current != null) {
            arguments.putString(ARG_LATITUDE, PickedLocation.text(current.latitude()));
            arguments.putString(ARG_LONGITUDE, PickedLocation.text(current.longitude()));
        }
        return arguments;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        MapLibre.getInstance(requireContext());
        binding = FragmentMemoryLocationPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(MemoryLocationPickerViewModel.class);
        Bundle arguments = getArguments();
        String latitude = arguments == null ? null : arguments.getString(ARG_LATITUDE);
        String longitude = arguments == null ? null : arguments.getString(ARG_LONGITUDE);
        viewModel.start(latitude == null || longitude == null ? null : MemoryFormRules.location(latitude, longitude));

        binding.pickerMap.onCreate(savedInstanceState);
        binding.pickerMap.addOnDidFailLoadingMapListener(message -> {
            if (binding != null) binding.pickerTilesError.setVisibility(View.VISIBLE);
        });
        binding.pickerMap.getMapAsync(this::onMapReady);
        binding.pickerBack.setOnClickListener(button -> close(null));
        binding.pickerConfirm.setOnClickListener(button -> {
            MemoryApi.Location picked = viewModel.picked().getValue();
            if (picked != null) close(PickedLocation.chosen(picked));
        });
        binding.pickerRemove.setVisibility(viewModel.hadLocation() ? View.VISIBLE : View.GONE);
        binding.pickerRemove.setOnClickListener(button -> close(PickedLocation.removed()));
        viewModel.picked().observe(getViewLifecycleOwner(), this::render);
    }

    private void onMapReady(@NonNull MapLibreMap loaded) {
        if (binding == null) return;
        map = loaded;
        loaded.getUiSettings().setRotateGesturesEnabled(false);
        loaded.getUiSettings().setTiltGesturesEnabled(false);
        MemoryApi.Location picked = viewModel.picked().getValue();
        loaded.moveCamera(picked == null ? CameraUpdateFactory.newLatLngZoom(START, START_ZOOM)
                : CameraUpdateFactory.newLatLngZoom(new LatLng(picked.latitude(), picked.longitude()), PICKED_ZOOM));
        loaded.addOnMapClickListener(tapListener);
        loaded.setStyle(new Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL), loadedStyle -> {
            if (binding == null) return;
            style = loadedStyle;
            loadedStyle.addSource(new GeoJsonSource(SOURCE));
            loadedStyle.addLayer(new CircleLayer(LAYER, SOURCE).withProperties(
                    PropertyFactory.circleColor(ContextCompat.getColor(requireContext(), R.color.ml_primary)),
                    PropertyFactory.circleRadius(PIN_RADIUS),
                    PropertyFactory.circleStrokeColor(ContextCompat.getColor(requireContext(), R.color.ml_surface)),
                    PropertyFactory.circleStrokeWidth(PIN_STROKE)));
            showPin(viewModel.picked().getValue());
        });
    }

    private boolean onMapTap(@NonNull LatLng tapped) {
        viewModel.pick(tapped.getLatitude(), tapped.getLongitude());
        return true;
    }

    private void render(@Nullable MemoryApi.Location picked) {
        Resources resources = getResources();
        binding.pickerCoordinates.setText(picked == null ? getString(R.string.memory_location_picker_none)
                : MemoryLabels.coordinates(picked,
                        resources.getString(R.string.memory_direction_north),
                        resources.getString(R.string.memory_direction_south),
                        resources.getString(R.string.memory_direction_east),
                        resources.getString(R.string.memory_direction_west)));
        binding.pickerConfirm.setEnabled(picked != null);
        showPin(picked);
    }

    private void showPin(@Nullable MemoryApi.Location picked) {
        if (style == null) return;
        GeoJsonSource source = style.getSourceAs(SOURCE);
        if (source == null) return;
        source.setGeoJson(picked == null ? FeatureCollection.fromFeatures(Collections.emptyList())
                : FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(picked.longitude(), picked.latitude()))));
    }

    /** Leaves the picker; {@code result} null means "nothing changed". A second tap is ignored. */
    private void close(@Nullable double[] result) {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current == null || current.getId() != R.id.memoryLocationPickerFragment) return;
        NavBackStackEntry form = navController.getPreviousBackStackEntry();
        if (result != null && form != null) form.getSavedStateHandle().set(PickedLocation.RESULT, result);
        navController.popBackStack();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (binding != null) binding.pickerMap.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) binding.pickerMap.onResume();
    }

    @Override
    public void onPause() {
        if (binding != null) binding.pickerMap.onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        if (binding != null) binding.pickerMap.onStop();
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding != null) binding.pickerMap.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (map != null) map.removeOnMapClickListener(tapListener);
        binding.pickerMap.onDestroy();
        map = null;
        style = null;
        binding = null;
        super.onDestroyView();
    }
}
