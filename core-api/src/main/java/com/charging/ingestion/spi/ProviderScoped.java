package com.charging.ingestion.spi;

/**
 * Marks a component as belonging to exactly one charging network.
 *
 * <p>Implemented by every capability in this package so {@link ProviderRegistry}
 * can index them uniformly, and so a reader can tell at a glance which classes
 * are allowed to contain provider-specific knowledge: these, and nothing above
 * {@code ingestion/}.
 */
public interface ProviderScoped {

    /**
     * Stable machine identifier for the network, e.g. {@code "zes"}.
     *
     * <p>Doubles as the dataset-name discriminator: every index in the chain is
     * {@code {providerId}_{dataset}-{date}}, so this value is part of the
     * storage contract, not just a log label. Lower-case, no separators.
     */
    String providerId();
}
