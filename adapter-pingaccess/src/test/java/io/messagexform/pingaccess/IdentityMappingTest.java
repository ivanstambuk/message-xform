package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pingidentity.pa.sdk.http.Exchange;
import com.pingidentity.pa.sdk.identity.Identity;
import com.pingidentity.pa.sdk.identity.OAuthTokenMetadata;
import com.pingidentity.pa.sdk.identity.SessionStateSupport;
import io.messagexform.core.model.SessionContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PingAccessAdapter#buildSessionContext(Exchange)}
 * (T-002-18, T-002-19, FR-002-06).
 *
 * <p>
 * Verifies the 4-layer flat merge of Identity → $session, null identity
 * handling, null OAuthTokenMetadata handling, layer precedence, and
 * type conversion from Jackson {@link JsonNode} to plain Java objects.
 */
class IdentityMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PingAccessAdapter adapter;
    private Exchange exchange;
    private Identity identity;
    private OAuthTokenMetadata oauthMetadata;
    private SessionStateSupport sessionState;

    @BeforeEach
    void setUp() {
        adapter = new PingAccessAdapter(MAPPER);
        exchange = mock(Exchange.class);
        identity = mock(Identity.class);
        oauthMetadata = mock(OAuthTokenMetadata.class);
        sessionState = mock(SessionStateSupport.class);
    }

    // ---- T-002-18: Layers 1–3 ----

    @Nested
    class Layers123 {

        @Test
        void layer1IdentityGettersAreMapped() {
            Instant expiry = Instant.parse("2026-12-31T23:59:59Z");
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn("bob.jensen");
            when(identity.getTrackingId()).thenReturn("tx-001");
            when(identity.getTokenId()).thenReturn("tok-abc");
            when(identity.getTokenExpiration()).thenReturn(expiry);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("subject")).isEqualTo("bjensen");
            assertThat(session.get("mappedSubject")).isEqualTo("bob.jensen");
            assertThat(session.get("trackingId")).isEqualTo("tx-001");
            assertThat(session.get("tokenId")).isEqualTo("tok-abc");
            assertThat(session.get("tokenExpiration")).isEqualTo("2026-12-31T23:59:59Z");
        }

        @Test
        void nullTokenExpirationIsOmitted() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("user");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.has("tokenExpiration")).isFalse();
            // null fields are still included (as null values)
            assertThat(session.get("subject")).isEqualTo("user");
        }

        @Test
        void layer2OAuthMetadataIsMerged() {
            Instant expiresAt = Instant.parse("2026-06-15T12:00:00Z");
            Instant retrievedAt = Instant.parse("2026-06-15T11:00:00Z");

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(oauthMetadata);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            when(oauthMetadata.getClientId()).thenReturn("my-app");
            when(oauthMetadata.getScopes()).thenReturn(Set.of("openid", "email"));
            when(oauthMetadata.getTokenType()).thenReturn("Bearer");
            when(oauthMetadata.getRealm()).thenReturn("example");
            when(oauthMetadata.getExpiresAt()).thenReturn(expiresAt);
            when(oauthMetadata.getRetrievedAt()).thenReturn(retrievedAt);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("clientId")).isEqualTo("my-app");
            assertThat(session.get("tokenType")).isEqualTo("Bearer");
            assertThat(session.get("realm")).isEqualTo("example");
            assertThat(session.get("tokenExpiresAt")).isEqualTo("2026-06-15T12:00:00Z");
            assertThat(session.get("tokenRetrievedAt")).isEqualTo("2026-06-15T11:00:00Z");
            // scopes should be a collection
            @SuppressWarnings("unchecked")
            var scopes = (java.util.Collection<String>) session.get("scopes");
            assertThat(scopes).containsExactlyInAnyOrder("openid", "email");
        }

        @Test
        void nullOAuthMetadataSkipsLayer2() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.has("clientId")).isFalse();
            assertThat(session.has("scopes")).isFalse();
            assertThat(session.has("tokenType")).isFalse();
        }

        @Test
        void nullOAuthExpiryFieldsAreOmitted() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("user");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(oauthMetadata);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            when(oauthMetadata.getClientId()).thenReturn("app");
            when(oauthMetadata.getScopes()).thenReturn(null);
            when(oauthMetadata.getTokenType()).thenReturn(null);
            when(oauthMetadata.getRealm()).thenReturn(null);
            when(oauthMetadata.getExpiresAt()).thenReturn(null);
            when(oauthMetadata.getRetrievedAt()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("clientId")).isEqualTo("app");
            assertThat(session.has("tokenExpiresAt")).isFalse();
            assertThat(session.has("tokenRetrievedAt")).isFalse();
            // null scopes → empty list
            @SuppressWarnings("unchecked")
            var scopes = (java.util.Collection<String>) session.get("scopes");
            assertThat(scopes).isEmpty();
        }

        @Test
        void layer3AttributesAreSpreadFlat() {
            ObjectNode attrs = MAPPER.createObjectNode();
            attrs.put("email", "bjensen@example.com");
            ArrayNode groups = MAPPER.createArrayNode();
            groups.add("admins");
            groups.add("users");
            attrs.set("groups", groups);

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(attrs);

            SessionContext session = adapter.buildSessionContext(exchange);

            // L3 spread into flat namespace
            assertThat(session.get("email")).isEqualTo("bjensen@example.com");
            @SuppressWarnings("unchecked")
            var groupList = (List<String>) session.get("groups");
            assertThat(groupList).containsExactly("admins", "users");
        }

        @Test
        void layer3OverridesLayer1OnKeyCollision() {
            // L1 sets "subject" = "pa-subject"
            // L3 has a claim also called "subject" = "oidc-subject"
            // → L3 should win
            ObjectNode attrs = MAPPER.createObjectNode();
            attrs.put("subject", "oidc-subject");

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("pa-subject");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(attrs);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("subject")).isEqualTo("oidc-subject");
        }

        @Test
        void nullAttributesSkipsLayer3() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("user");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            // Only L1 fields present
            assertThat(session.get("subject")).isEqualTo("user");
            assertThat(session.has("email")).isFalse();
        }
    }

    // ---- T-002-19: Layer 4 + null identity ----

    @Nested
    class Layer4AndNullIdentity {

        @Test
        void layer4SessionStateIsMerged() {
            ObjectNode authzNode = MAPPER.createObjectNode();
            authzNode.put("decision", "PERMIT");

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(sessionState);
            when(identity.getAttributes()).thenReturn(null);

            when(sessionState.getAttributes()).thenReturn(Map.of(
                    "authzCache", MAPPER.getNodeFactory().textNode("PERMIT"),
                    "lastAccess", MAPPER.getNodeFactory().textNode("2026-02-14T10:00:00Z")));

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("authzCache")).isEqualTo("PERMIT");
            assertThat(session.get("lastAccess")).isEqualTo("2026-02-14T10:00:00Z");
        }

        @Test
        void layer4OverridesAllPriorLayers() {
            // L1: subject = "pa-user"
            // L3: subject = "oidc-user"
            // L4: subject = "session-override"
            // → L4 wins
            ObjectNode attrs = MAPPER.createObjectNode();
            attrs.put("subject", "oidc-user");

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("pa-user");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(sessionState);
            when(identity.getAttributes()).thenReturn(attrs);

            when(sessionState.getAttributes()).thenReturn(Map.of(
                    "subject", MAPPER.getNodeFactory().textNode("session-override")));

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("subject")).isEqualTo("session-override");
        }

        @Test
        void nullSessionStateSupportSkipsLayer4() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(null);
            when(identity.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("subject")).isEqualTo("bjensen");
            assertThat(session.has("authzCache")).isFalse();
        }

        @Test
        void nullSessionStateAttributesSkipsLayer4() {
            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("bjensen");
            when(identity.getMappedSubject()).thenReturn(null);
            when(identity.getTrackingId()).thenReturn(null);
            when(identity.getTokenId()).thenReturn(null);
            when(identity.getTokenExpiration()).thenReturn(null);
            when(identity.getOAuthTokenMetadata()).thenReturn(null);
            when(identity.getSessionStateSupport()).thenReturn(sessionState);
            when(identity.getAttributes()).thenReturn(null);

            when(sessionState.getAttributes()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session.get("subject")).isEqualTo("bjensen");
        }

        @Test
        void nullIdentityReturnsEmptySession() {
            when(exchange.getIdentity()).thenReturn(null);

            SessionContext session = adapter.buildSessionContext(exchange);

            assertThat(session).isEqualTo(SessionContext.empty());
            assertThat(session.isEmpty()).isTrue();
        }

        @Test
        void fullFourLayerPrecedence() {
            // Full stack: L1 → L2 → L3 → L4 precedence validation
            Instant expiry = Instant.parse("2026-12-31T23:59:59Z");

            // L3 attributes: override L1 subject, add email
            ObjectNode attrs = MAPPER.createObjectNode();
            attrs.put("subject", "oidc-subject");
            attrs.put("email", "bjensen@example.com");

            // L4 session state: override email from L3
            Map<String, JsonNode> stateMap = Map.of(
                    "email", MAPPER.getNodeFactory().textNode("override@example.com"),
                    "authzCache", MAPPER.getNodeFactory().textNode("PERMIT"));

            when(exchange.getIdentity()).thenReturn(identity);
            when(identity.getSubject()).thenReturn("pa-subject");
            when(identity.getMappedSubject()).thenReturn("mapped");
            when(identity.getTrackingId()).thenReturn("tx-001");
            when(identity.getTokenId()).thenReturn("tok-123");
            when(identity.getTokenExpiration()).thenReturn(expiry);
            when(identity.getOAuthTokenMetadata()).thenReturn(oauthMetadata);
            when(identity.getSessionStateSupport()).thenReturn(sessionState);
            when(identity.getAttributes()).thenReturn(attrs);

            when(oauthMetadata.getClientId()).thenReturn("my-app");
            when(oauthMetadata.getScopes()).thenReturn(Set.of("openid"));
            when(oauthMetadata.getTokenType()).thenReturn("Bearer");
            when(oauthMetadata.getRealm()).thenReturn(null);
            when(oauthMetadata.getExpiresAt()).thenReturn(null);
            when(oauthMetadata.getRetrievedAt()).thenReturn(null);

            when(sessionState.getAttributes()).thenReturn(stateMap);

            SessionContext session = adapter.buildSessionContext(exchange);

            // L1 → subject overridden by L3
            assertThat(session.get("subject")).isEqualTo("oidc-subject");
            // L1 → mappedSubject not overridden
            assertThat(session.get("mappedSubject")).isEqualTo("mapped");
            // L1 → trackingId not overridden
            assertThat(session.get("trackingId")).isEqualTo("tx-001");
            // L1 → tokenId not overridden
            assertThat(session.get("tokenId")).isEqualTo("tok-123");
            // L1 → tokenExpiration not overridden
            assertThat(session.get("tokenExpiration")).isEqualTo("2026-12-31T23:59:59Z");
            // L2 → clientId
            assertThat(session.get("clientId")).isEqualTo("my-app");
            // L2 → tokenType
            assertThat(session.get("tokenType")).isEqualTo("Bearer");
            // L3 → email overridden by L4
            assertThat(session.get("email")).isEqualTo("override@example.com");
            // L4 → authzCache (new key)
            assertThat(session.get("authzCache")).isEqualTo("PERMIT");
        }
    }
}
