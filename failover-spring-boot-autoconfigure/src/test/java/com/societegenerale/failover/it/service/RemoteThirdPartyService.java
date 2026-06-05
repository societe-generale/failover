package com.societegenerale.failover.it.service;

import com.societegenerale.failover.it.domain.ThirdPartiesResult;
import com.societegenerale.failover.it.domain.ThirdParty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class RemoteThirdPartyService {

    /**
     * Immutable referential: IDs 1–10, names "ThirdParty-1".."ThirdParty-10",
     * scores 100, 200, …, 1000.
     */
    private static final Map<String, ThirdParty> REFERENTIAL =
            IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> new ThirdParty((long) i, "ThirdParty-" + i, i * 100))
                    .collect(Collectors.toUnmodifiableMap(tp -> String.valueOf(tp.getId()), tp -> tp));

    private final ThirdPartyServiceController ctrl;

    public ThirdParty fetchOne(String id) {
        if (ctrl.primaryFails.get()) throw new RuntimeException("Primary [fetchOne] unavailable — simulated failure");
        return getCopyOf(id);
    }

    public ThirdPartiesResult fetchAllIn(String csvIds) {
        if (ctrl.primaryFails.get()) throw new RuntimeException("Primary [fetchAll] unavailable — simulated failure");
        List<ThirdParty> parties = Arrays.stream(csvIds.split(","))
                .map(String::trim)
                .map(RemoteThirdPartyService::getCopyOf)
                .toList();
        ThirdPartiesResult result = new ThirdPartiesResult();
        result.setThirdParties(parties);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a fresh copy of the referential entry for {@code id}, or {@code null} if absent. */
    public static ThirdParty getCopyOf(String id) {
        ThirdParty source = REFERENTIAL.get(id);
        return source == null ? null : new ThirdParty(source.getId(), source.getName(), source.getScore());
    }
}
