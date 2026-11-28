package com.memorylane.feature.memories.ui;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.memorylane.feature.memories.data.MemoryApi;

/**
 * The point on the map picker (ML-15). Kept here so a rotation keeps the user's choice. Needs no
 * location permission: the point comes only from where the user taps.
 */
public final class MemoryLocationPickerViewModel extends ViewModel {
    private final MutableLiveData<MemoryApi.Location> picked = new MutableLiveData<>(null);
    private boolean started;
    @Nullable private MemoryApi.Location initial;

    public LiveData<MemoryApi.Location> picked() { return picked; }

    /** The form's current location; applied once, a recreated view keeps the newer choice. */
    void start(@Nullable MemoryApi.Location formLocation) {
        if (started) return;
        started = true;
        initial = formLocation;
        picked.setValue(formLocation);
    }

    /** True when the form already had a location, so "Konumu kaldır" means something. */
    boolean hadLocation() { return initial != null; }

    /** A tap on the map; a point that is not a usable coordinate is ignored. */
    void pick(double latitude, double longitude) {
        MemoryApi.Location location = PickedLocation.fromMap(latitude, longitude);
        if (location != null) picked.setValue(location);
    }
}
