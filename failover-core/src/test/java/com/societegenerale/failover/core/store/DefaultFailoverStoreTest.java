package com.societegenerale.failover.core.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultFailoverStoreTest {

    private static final Instant AS_OF = Instant.parse("2024-01-01T10:00:00Z");

    private static final Instant EXPIRE_ON = Instant.parse("2024-01-02T10:00:00Z");

    @Mock
    private FailoverStore<String> delegate;

    private DefaultFailoverStore<String> store;

    @BeforeEach
    void setUp() {
        store = new DefaultFailoverStore<>(delegate);
    }

    // --- store() ---

    @Test
    @DisplayName("store delegates with up to date false")
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
    @DisplayName("store propagates failover store exception")
    void storePropagatesFailoverStoreException() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).store(any());

        assertThatThrownBy(() -> store.store(original)).isInstanceOf(FailoverStoreException.class);
    }

    // --- delete() ---

    @Test
    @DisplayName("delete delegates with up to date false")
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
    @DisplayName("delete propagates failover store exception")
    void deletePropagatesFailoverStoreException() throws FailoverStoreException {
        ReferentialPayload<String> original = new ReferentialPayload<>("name", "key", true, AS_OF, EXPIRE_ON, "payload");
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).delete(any());

        assertThatThrownBy(() -> store.delete(original)).isInstanceOf(FailoverStoreException.class);
    }

    // --- find() ---

    @Test
    @DisplayName("find present result returns with up to date false")
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
    @DisplayName("find empty result returns empty")
    void findEmptyResultReturnsEmpty() throws FailoverStoreException {
        when(delegate.find("name", "key")).thenReturn(Optional.empty());

        Optional<ReferentialPayload<String>> result = store.find("name", "key");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("find propagates failover store exception")
    void findPropagatesFailoverStoreException() throws FailoverStoreException {
        when(delegate.find("name", "key")).thenThrow(new FailoverStoreException("error", new RuntimeException()));

        assertThatThrownBy(() -> store.find("name", "key")).isInstanceOf(FailoverStoreException.class);
    }

    // --- cleanByExpiry() ---

    @Test
    @DisplayName("clean by expiry delegates directly")
    void cleanByExpiryDelegatesDirectly() throws FailoverStoreException {
        Instant expiry = Instant.now();

        store.cleanByExpiry(expiry);

        verify(delegate).cleanByExpiry(expiry);
    }

    @Test
    @DisplayName("clean by expiry propagates failover store exception")
    void cleanByExpiryPropagatesFailoverStoreException() throws FailoverStoreException {
        Instant expiry = Instant.now();
        doThrow(new FailoverStoreException("error", new RuntimeException())).when(delegate).cleanByExpiry(any());

        assertThatThrownBy(() -> store.cleanByExpiry(expiry)).isInstanceOf(FailoverStoreException.class);
    }

    @Test
    @DisplayName("findAll delegates and returns defensive copies with upToDate=false")
    void findAllDelegatesAndReturnsDefensiveCopiesWithUpToDateFalse() throws FailoverStoreException {
        ReferentialPayload<String> r1 = new ReferentialPayload<>("name", "k1", true, AS_OF, EXPIRE_ON, "p1");
        ReferentialPayload<String> r2 = new ReferentialPayload<>("name", "k2", true, AS_OF, EXPIRE_ON, "p2");
        given(delegate.findAll("name")).willReturn(List.of(r1, r2));

        List<ReferentialPayload<String>> result = store.findAll("name");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).isUpToDate()).isFalse();
        assertThat(result.get(0).getPayload()).isEqualTo("p1");
        assertThat(result.get(1).isUpToDate()).isFalse();
        assertThat(result.get(1).getPayload()).isEqualTo("p2");
        verify(delegate).findAll("name");
    }

    @Test
    @DisplayName("findAll returns empty list when delegate returns empty")
    void findAllReturnsEmptyListWhenDelegateReturnsEmpty() throws FailoverStoreException {
        given(delegate.findAll("name")).willReturn(List.of());

        List<ReferentialPayload<String>> result = store.findAll("name");

        assertThat(result).isEmpty();
        verify(delegate).findAll("name");
    }

    // --- liveEntryCount() forwarding ---

    @Test
    @DisplayName("liveEntryCount is unsupported and returns 0 when the delegate is not size-aware")
    void liveEntryCountUnsupportedWhenDelegateNotSizeAware() {
        assertThat(store.liveEntryCountSupported()).isFalse();
        assertThat(store.liveEntryCount("name")).isZero();
    }

    @Test
    @DisplayName("liveEntryCount forwards to a size-aware delegate")
    void liveEntryCountForwardsToSizeAwareDelegate() {
        DefaultFailoverStore<String> sizeAware = new DefaultFailoverStore<>(new SizeAwareStore());

        assertThat(sizeAware.liveEntryCountSupported()).isTrue();
        assertThat(sizeAware.liveEntryCount("name")).isEqualTo(7L);
    }

    @Test
    @DisplayName("liveEntryCount unsupported when a size-aware delegate reports counting unsupported")
    void liveEntryCountUnsupportedWhenDelegateReportsUnsupported() {
        DefaultFailoverStore<String> store = new DefaultFailoverStore<>(new SizeAwareStore() {
            @Override public boolean liveEntryCountSupported() { return false; }
        });

        assertThat(store.liveEntryCountSupported()).isFalse();   // && short-circuits on the delegate's flag
    }

    /** A delegate that is both a store and size-aware, for the forwarding test. */
    static class SizeAwareStore implements FailoverStore<String>, FailoverStoreSizeAware {
        @Override public void store(ReferentialPayload<String> p) { }
        @Override public void delete(ReferentialPayload<String> p) { }
        @Override public Optional<ReferentialPayload<String>> find(String name, String key) { return Optional.empty(); }
        @Override public List<ReferentialPayload<String>> findAll(String name) { return List.of(); }
        @Override public void cleanByExpiry(Instant expiry) { }
        @Override public long liveEntryCount(String name) { return 7L; }
    }
}