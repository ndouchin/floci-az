package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The issuer URL is generated once and kept on the record, so it survives a restart in a persistent storage mode. */
class AksOidcIssuerRecordTest {

    @Test
    void theIssuerUrlIsPartOfThePersistedRecord() throws Exception {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setLocation("westeurope");
        AksWorkloadIdentity.enableIssuer(cluster, "t1");
        String url = cluster.getOidcIssuerUrl();
        ObjectMapper mapper = new ObjectMapper();
        ManagedCluster restored = mapper.readValue(mapper.writeValueAsString(cluster), ManagedCluster.class);
        assertEquals(url, restored.getOidcIssuerUrl());
        AksWorkloadIdentity.enableIssuer(restored, "t2");
        assertEquals(url, restored.getOidcIssuerUrl());
    }

    @Test
    void aRecordFromBeforeTheProfileWasTypedHasNoIssuer() throws Exception {
        ManagedCluster legacy = new ObjectMapper().readValue("{\"name\": \"old\"}", ManagedCluster.class);
        assertNull(legacy.getOidcIssuerUrl());
        assertEquals(false, legacy.oidcIssuerEnabled());
    }
}
