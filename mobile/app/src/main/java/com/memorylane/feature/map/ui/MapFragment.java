package com.memorylane.feature.map.ui;

import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.memorylane.BuildConfig;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentMapBinding;
import com.memorylane.feature.map.data.MapApi;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Map tab (ML-16): draws {@link MapUiState} on an OpenStreetMap map. Pins are a circle layer
 * fed by one GeoJSON source, so no marker bitmaps are needed; the selected pin is a second,
 * larger layer filtered to its id. A tap near a pin selects it, a tap elsewhere closes the card.
 */
public class MapFragment extends Fragment {
    private static final String SOURCE = "memories";
    private static final String PINS = "memory-pins";
    private static final String SELECTED_PIN = "memory-pin-selected";
    private static final String ID = "id";
    /** Argument name of memoryDetailFragment in nav_graph.xml. */
    private static final String ARG_MEMORY_ID = "memoryId";
    /** Pin sizes are in map pixels, which MapLibre already scales by screen density. */
    private static final float PIN_RADIUS = 9f;
    private static final float SELECTED_PIN_RADIUS = 14f;
    private static final float PIN_STROKE = 3f;
    private static final double SINGLE_PIN_ZOOM = 12;
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMapBinding binding;
    private AppContainer app;
    private MapViewModel viewModel;
    @Nullable private MapLibreMap map;
    @Nullable private Style style;
    /** Points the camera was last fitted to; a refresh with the same points keeps the user's view. */
    @Nullable private List<MapApi.Point> framed;
    /** Memory whose cover the card shows, so a re-delivered state does not reload it. */
    @Nullable private String cardMemory;
    /** False on the first view of this fragment; a view recreated on return reloads the points. */
    private boolean shownBefore;
    private final MapLibreMap.OnMapClickListener tapListener = this::onMapTap;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Must run before a MapView is inflated; repeated calls return the same instance.
        MapLibre.getInstance(requireContext());
        binding = FragmentMapBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        viewModel = new ViewModelProvider(this, MapViewModel.factory(app.getMapRepository())).get(MapViewModel.class);
        framed = null;
        cardMemory = null;

        binding.mapView.onCreate(savedInstanceState);
        // Tiles failing is not "memories could not be loaded": pins and the card keep working.
        binding.mapView.addOnDidFailLoadingMapListener(message -> {
            if (binding != null) binding.mapTilesError.setVisibility(View.VISIBLE);
        });
        binding.mapView.getMapAsync(this::onMapReady);

        binding.mapZoomIn.setOnClickListener(button -> zoom(true));
        binding.mapZoomOut.setOnClickListener(button -> zoom(false));
        binding.mapAdd.setOnClickListener(button -> navigate(R.id.action_mapFragment_to_memoryCreateFragment, null));
        binding.mapCard.setOnClickListener(card -> openSelected());
        binding.mapCardOpen.setOnClickListener(button -> openSelected());

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.start(app.authorization());
        // Back from a detail, an edit or a new memory: pins may have changed meanwhile.
        if (shownBefore) viewModel.refresh();
        shownBefore = true;
    }

    private void onMapReady(@NonNull MapLibreMap loaded) {
        if (binding == null) return;
        map = loaded;
        UiSettings ui = loaded.getUiSettings();
        ui.setRotateGesturesEnabled(false);
        ui.setTiltGesturesEnabled(false);
        ui.setCompassEnabled(false);
        // The OpenStreetMap attribution must stay visible; the card covers the bottom edge.
        int margin = getResources().getDimensionPixelSize(R.dimen.ml_space_md);
        int belowCount = getResources().getDimensionPixelSize(R.dimen.map_attribution_top);
        ui.setLogoGravity(Gravity.TOP | Gravity.START);
        ui.setLogoMargins(margin, belowCount, 0, 0);
        ui.setAttributionGravity(Gravity.TOP | Gravity.START);
        ui.setAttributionMargins(margin * 6, belowCount, 0, 0);
        loaded.addOnMapClickListener(tapListener);
        loaded.setStyle(new Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL), loadedStyle -> {
            if (binding == null) return;
            style = loadedStyle;
            addPinLayers(loadedStyle);
            MapUiState state = viewModel.getState().getValue();
            if (state != null) showPins(state);
        });
    }

    private void addPinLayers(Style loadedStyle) {
        int pink = ContextCompat.getColor(requireContext(), R.color.ml_primary);
        int white = ContextCompat.getColor(requireContext(), R.color.ml_surface);
        loadedStyle.addSource(new GeoJsonSource(SOURCE));
        loadedStyle.addLayer(new CircleLayer(PINS, SOURCE).withProperties(
                PropertyFactory.circleColor(pink),
                PropertyFactory.circleRadius(PIN_RADIUS),
                PropertyFactory.circleStrokeColor(white),
                PropertyFactory.circleStrokeWidth(PIN_STROKE)));
        loadedStyle.addLayer(new CircleLayer(SELECTED_PIN, SOURCE)
                .withFilter(selectedFilter(null))
                .withProperties(
                        PropertyFactory.circleColor(pink),
                        PropertyFactory.circleRadius(SELECTED_PIN_RADIUS),
                        PropertyFactory.circleStrokeColor(white),
                        PropertyFactory.circleStrokeWidth(PIN_STROKE)));
    }

    private void render(MapUiState state) {
        MapUiState.Status status = state.getStatus();
        boolean content = status == MapUiState.Status.CONTENT;
        binding.mapStateCard.setVisibility(content || status == MapUiState.Status.SIGNED_OUT ? View.GONE : View.VISIBLE);
        switch (status) {
            case SIGNED_OUT:
                // Normally already done by AuthInterceptor; the session layer opens the login screen.
                app.clearSession();
                binding.mapState.hide();
                break;
            case LOADING:
                binding.mapState.showLoading(getString(R.string.map_loading));
                break;
            case EMPTY:
                binding.mapState.showEmpty(getString(R.string.map_empty_title), getString(R.string.map_empty_message));
                break;
            case ERROR:
                ApiError error = state.getError();
                binding.mapState.showError(getString(R.string.map_error_title),
                        ErrorMessages.describe(requireContext(), error != null ? error : ApiError.unexpected()),
                        viewModel::retry);
                break;
            case CONTENT:
            default:
                binding.mapState.hide();
                break;
        }
        showCount(state);
        showCard(state.getSelected());
        showPins(state);
    }

    private void showCount(MapUiState state) {
        int count = state.getPoints().size();
        binding.mapCount.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        binding.mapCount.setText(state.isTruncated()
                ? getString(R.string.map_count_truncated, count)
                : getResources().getQuantityString(R.plurals.map_count, count, count));
    }

    private void showPins(MapUiState state) {
        if (style == null || map == null) return;
        GeoJsonSource source = style.getSourceAs(SOURCE);
        Layer selectedLayer = style.getLayer(SELECTED_PIN);
        if (source == null || !(selectedLayer instanceof CircleLayer)) return;
        List<Feature> features = new ArrayList<>();
        for (MapApi.Point point : state.getPoints()) {
            Feature feature = Feature.fromGeometry(Point.fromLngLat(point.longitude(), point.latitude()));
            feature.addStringProperty(ID, point.id());
            features.add(feature);
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features));
        MapApi.Point selected = state.getSelected();
        ((CircleLayer) selectedLayer).setFilter(selectedFilter(selected == null ? null : selected.id()));
        if (!state.getPoints().isEmpty() && !state.getPoints().equals(framed)) {
            frame(state.getPoints());
        }
    }

    /** Moves the camera so every pin is on screen. */
    private void frame(List<MapApi.Point> points) {
        framed = points;
        if (points.size() == 1) {
            MapApi.Point only = points.get(0);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(only.latitude(), only.longitude()), SINGLE_PIN_ZOOM));
            return;
        }
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (MapApi.Point point : points) bounds.include(new LatLng(point.latitude(), point.longitude()));
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),
                getResources().getDimensionPixelSize(R.dimen.map_fit_padding)));
    }

    private static Expression selectedFilter(@Nullable String memoryId) {
        // An empty id matches no pin, which hides the selected layer.
        return Expression.eq(Expression.get(ID), memoryId == null ? "" : memoryId);
    }

    private boolean onMapTap(@NonNull LatLng tapped) {
        if (map == null) return false;
        PointF at = map.getProjection().toScreenLocation(tapped);
        float reach = getResources().getDimension(R.dimen.map_pin_touch);
        List<Feature> hits = map.queryRenderedFeatures(
                new RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach), PINS);
        if (hits.isEmpty()) {
            viewModel.clearSelection();
            return false;
        }
        viewModel.select(hits.get(0).getStringProperty(ID));
        return true;
    }

    private void showCard(@Nullable MapApi.Point selected) {
        binding.mapCard.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        if (selected == null) {
            cardMemory = null;
            app.getPhotoImageLoader().cancel(binding.mapCardCover);
            return;
        }
        binding.mapCardTitle.setText(selected.title());
        binding.mapCardDate.setText(DateTimeFormatter.ofPattern(getString(R.string.map_card_date_pattern), TURKISH)
                .format(LocalDate.parse(selected.memoryDate())));
        if (Objects.equals(cardMemory, selected.id())) return;
        cardMemory = selected.id();
        binding.mapCardCover.setImageResource(R.color.memory_pink_soft);
        if (selected.coverPhotoId() == null) {
            app.getPhotoImageLoader().cancel(binding.mapCardCover);
            binding.mapCardCover.setImageResource(R.drawable.memory_photo_missing);
            return;
        }
        int size = getResources().getDimensionPixelSize(R.dimen.map_card_cover);
        app.getPhotoImageLoader().load(binding.mapCardCover, selected.id(), selected.coverPhotoId(), size, size,
                bitmap -> {
                    if (binding == null) return;
                    if (bitmap != null) binding.mapCardCover.setImageBitmap(bitmap);
                    else binding.mapCardCover.setImageResource(R.drawable.memory_photo_missing);
                });
    }

    private void zoom(boolean in) {
        if (map != null) map.animateCamera(in ? CameraUpdateFactory.zoomIn() : CameraUpdateFactory.zoomOut());
    }

    private void openSelected() {
        MapUiState state = viewModel.getState().getValue();
        MapApi.Point selected = state == null ? null : state.getSelected();
        if (selected == null) return;
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEMORY_ID, selected.id());
        navigate(R.id.action_mapFragment_to_memoryDetailFragment, arguments);
    }

    /** Ignores a second tap while the first navigation is already under way. */
    private void navigate(int action, @Nullable Bundle arguments) {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current != null && current.getId() == R.id.mapFragment) {
            navController.navigate(action, arguments);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (binding != null) binding.mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) binding.mapView.onResume();
    }

    @Override
    public void onPause() {
        if (binding != null) binding.mapView.onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        if (binding != null) binding.mapView.onStop();
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding != null) binding.mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        app.getPhotoImageLoader().cancel(binding.mapCardCover);
        if (map != null) map.removeOnMapClickListener(tapListener);
        binding.mapView.onDestroy();
        map = null;
        style = null;
        binding = null;
        super.onDestroyView();
    }
}
