package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.memories.data.MemoryDeleteRepository;

import java.util.Objects;

/**
 * The delete confirmation panel of one memory (ML-13). Nothing is deleted until the user confirms;
 * then exactly one request runs and a second tap is ignored.
 *
 * <ul>
 *   <li>204 and 404 both end in DELETED: after a delete whose answer was lost, the retry finds the
 *       memory already gone (contract: "DELETE sonrası doğrulamada silinmiş kaynak için 404 beklenir").
 *       For another account's memory the server answers 404 too and has deleted nothing.</li>
 *   <li>401 is SIGNED_OUT; the session layer has already ended the session.</li>
 *   <li>Any other failure is FAILED with the error; the memory is unchanged and the panel offers a retry.</li>
 * </ul>
 */
public final class MemoryDeleteViewModel extends ViewModel {
    public enum Status { READY, DELETING, DELETED, FAILED, SIGNED_OUT }

    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;

    private final MemoryDeleteRepository repository;
    private final String memoryId;
    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.READY);
    @Nullable private ApiError error;
    @Nullable private Cancellable request;

    public MemoryDeleteViewModel(@NonNull MemoryDeleteRepository repository, @NonNull String memoryId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.memoryId = Objects.requireNonNull(memoryId, "memoryId");
    }

    public static ViewModelProvider.Factory factory(MemoryDeleteRepository repository, String memoryId) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoryDeleteViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoryDeleteViewModel(repository, memoryId));
            }
        };
    }

    public LiveData<Status> getStatus() { return status; }

    /** Why the last attempt failed; non-null exactly in FAILED. */
    @Nullable public ApiError getError() { return error; }

    /** Confirms the delete; also the retry after FAILED. Null session is SIGNED_OUT without a request. */
    public void delete(@Nullable String authorization) {
        Status current = status.getValue();
        if (current == Status.DELETING || current == Status.DELETED) return;
        if (authorization == null) {
            status.setValue(Status.SIGNED_OUT);
            return;
        }
        error = null;
        status.setValue(Status.DELETING);
        request = repository.deleteMemory(authorization, memoryId, this::onResult);
    }

    private void onResult(ApiResult<Void> result) {
        request = null;
        ApiError failure = result.getError();
        if (result.isSuccess() || (failure != null && failure.getHttpStatus() == NOT_FOUND)) {
            status.setValue(Status.DELETED);
        } else if (failure != null && failure.getHttpStatus() == UNAUTHORIZED) {
            status.setValue(Status.SIGNED_OUT);
        } else {
            error = failure != null ? failure : ApiError.unexpected();
            status.setValue(Status.FAILED);
        }
    }

    @Override
    protected void onCleared() {
        // The panel is not dismissable while DELETING, so this only runs when the whole screen goes.
        if (request != null) request.cancel();
        request = null;
    }
}
