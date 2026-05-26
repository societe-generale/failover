package com.societegenerale.failover.core.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultFailoverStoreTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2024, 1, 1, 10, 0);

    private static final LocalDateTime EXPIRE_ON = LocalDateTime.of(2024, 1, 2, 10, 0);

    @Mock
    private FailoverStore<String> delegate;

    private DefaultFailoverStore<String> store;

    @BeforeEach
    void setUp() {
        store = new DefaultFailoverStore<>(delegate);
    }

    // --- store() ---

    @Test
    void storeDelegatesWithUpToDateFalse() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ReferentialPayload<String>> captor = ArgumentCaptor.forClass(ReferentialPayload.class);

        store.store(original);

        verify(delegate).store(captor.capture());
        ReferentialPayload<String> captured = captor.getValue();
        assertThat(captured.isUpToDate()).isFalse();
        assertThat(captured.getName()).isEqualTo("name");
        assertThat(captured.getKey()).isEqualTo("key");
        assertThat(captured.getAsOf()).isEqualTo(AS_OF);
        assertThat(captured.getExpireOn()).isEqualTo(EXPIRE_ON);
        assertThat(captured.getPayload()).isEqualTo("payload");
    }

    @Test
    void storePropagatesFailoverStoreException() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).store(any());

        assertThatThrownBy(() -> store.store(original)).isInstanceOf(FailoverStoreException.class);
    }

    // --- delete() ---

    @Test
    void deleteDelegatesWithUpToDateFalse() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ReferentialPayload<String>> captor = ArgumentCaptor.forClass(ReferentialPayload.class);

        store.delete(original);

        verify(delegate).delete(captor.capture());
        ReferentialPayload<String> captured = captor.getValue();
        assertThat(captured.isUpToDate()).isFalse();
        assertThat(captured.getName()).isEqualTo("name");
        assertThat(captured.getKey()).isEqualTo("key");
        assertThat(captured.getAsOf()).isEqualTo(AS_OF);
        assertThat(captured.getExpireOn()).isEqualTo(EXPIRE_ON);
        assertThat(captured.getPayload()).isEqualTo("payload");
    }

    @Test
    void deletePropagatesFailoverStoreException() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).delete(any());

        assertThatThrownBy(() -> store.delete(original)).isInstanceOf(FailoverStoreException.class);
    }

    // --- find() ---

    @Test
    void findPresentResultReturnsWithUpToDateFalse() throws FailoverStoreException {
        ReferentialPayload<String> found = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        when(delegate.find("name", "key")).thenReturn(Optional.of(found));

        Optional<ReferentialPayload<String>> result = store.find("name", "key");

        assertThat(result).isPresent();
        assertThat(result.get().isUpToDate()).isFalse();
        assertThat(result.get().getName()).isEqualTo("name");
        assertThat(result.get().getKey()).isEqualTo("key");
        assertThat(result.get().getAsOf()).isEqualTo(AS_OF);
        assertThat(result.get().getExpireOn()).isEqualTo(EXPIRE_ON);
        assertThat(result.get().getPayload()).isEqualTo("payload");
    }

    @Test
    void findEmptyResultReturnsEmpty() throws FailoverStoreException {
        when(delegate.find("name", "key")).thenReturn(Optional.empty());

        Optional<ReferentialPayload<String>> result = store.find("name", "key");

        assertThat(result).isEmpty();
    }

    @Test
    void findPropagatesFailoverStoreException() throws FailoverStoreException {
        when(delegate.find("name", "key")).thenThrow(new FailoverStoreException("error", new RuntimeException()));

        assertThatThrownBy(() -> store.find("name", "key")).isInstanceOf(FailoverStoreException.class);
    }

    // --- cleanByExpiry() ---

    @Test
    void cleanByExpiryDelegatesDirectly() throws FailoverStoreException {
        LocalDateTime expiry = LocalDateTime.now();

        store.cleanByExpiry(expiry);

        verify(delegate).cleanByExpiry(expiry);
    }

    @Test
    void cleanByExpiryPropagatesFailoverStoreException() throws FailoverStoreException {
        LocalDateTime expiry = LocalDateTime.now();
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).cleanByExpiry(any());

        assertThatThrownBy(() -> store.cleanByExpiry(expiry)).isInstanceOf(FailoverStoreException.class);
    }
}