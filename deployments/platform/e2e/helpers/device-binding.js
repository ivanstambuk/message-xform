/**
 * Device Binding Helper for Karate E2E Tests
 *
 * Implements PingAM's Device Binding / Device Signing Verifier flow using pure
 * JDK crypto. No external dependencies required.
 *
 * Device Binding uses ForgeRock's proprietary DeviceBindingCallback and
 * DeviceSigningVerifierCallback with RS512-signed JWTs — distinct from
 * WebAuthn/FIDO2 passkeys.
 *
 * Setting authenticationType=NONE allows headless E2E testing with pure JDK
 * crypto — no biometrics, no SDK, no hardware.
 *
 * Usage from Karate:
 *   * def deviceBinding = call read('helpers/device-binding.js')
 *   * def kp = deviceBinding.generateKeyPair()
 *   * def jws = deviceBinding.buildBindingJws(challenge, userId, kp.privateKey, kp.kid)
 *   * def parsed = deviceBinding.parseBindingCallback(callbacks)
 *   * def sigParsed = deviceBinding.parseSigningCallback(callbacks)
 *
 * Reference: PingAM 8 DeviceBindingCallback / DeviceSigningVerifierCallback docs
 * See: deployments/platform/DEVICE-BINDING-PLAN.md
 */
function fn() {
    var KeyPairGenerator = Java.type('java.security.KeyPairGenerator');
    var Signature = Java.type('java.security.Signature');
    var Base64 = Java.type('java.util.Base64');
    var SecureRandom = Java.type('java.security.SecureRandom');
    var UUID = Java.type('java.util.UUID');
    var JString = Java.type('java.lang.String');
    var Arrays = Java.type('java.util.Arrays');

    var urlEncoder = Base64.getUrlEncoder().withoutPadding();
    var random = new SecureRandom();

    // ---- Base64url helpers ----

    /**
     * Base64url-encode a Java byte[].
     * @param bytes Java byte[]
     * @returns base64url string (no padding)
     */
    function b64url(bytes) {
        return urlEncoder.encodeToString(bytes);
    }

    /**
     * Base64url-encode a UTF-8 string.
     * @param str plain text string
     * @returns base64url string (no padding)
     */
    function b64urlStr(str) {
        return b64url(new JString(str).getBytes('UTF-8'));
    }

    // ---- Key pair generation ----

    /**
     * Generate a 2048-bit RSA key pair with a random KID (UUID).
     *
     * @returns { publicKey: java.security.PublicKey, privateKey: java.security.PrivateKey, kid: string }
     */
    function generateKeyPair() {
        var kpg = KeyPairGenerator.getInstance('RSA');
        kpg.initialize(2048, random);
        var keyPair = kpg.generateKeyPair();
        var kid = UUID.randomUUID().toString();

        return {
            publicKey: keyPair.getPublic(),
            privateKey: keyPair.getPrivate(),
            kid: kid
        };
    }

    // ---- JWS construction (RS512) ----

    /**
     * Build a compact JWS (RS512) for a Device Binding or Signing Verifier response.
     *
     * JWS structure:
     *   Header:  { "alg": "RS512", "kid": "<kid>" }
     *   Payload: { "sub": "<userId>", "challenge": "<challenge>", "exp": <ts+60>, "iat": <ts>, "nbf": <ts> }
     *
     * The challenge value is passed through verbatim (base64-encoded as received from AM).
     *
     * @param challenge  base64-encoded challenge string from the callback
     * @param userId     user ID string (DN or username) from the callback
     * @param privateKey java.security.PrivateKey (RSA)
     * @param kid        key identifier string (UUID)
     * @param publicKey  optional java.security.PublicKey — included in header for binding
     * @returns compact JWS string (header.payload.signature)
     */
    function buildJws(challenge, userId, privateKey, kid, publicKey) {
        var KeyFactory = Java.type('java.security.KeyFactory');
        var RSAPublicKeySpec = Java.type('java.security.spec.RSAPublicKeySpec');

        // Current epoch seconds
        var now = Math.floor(java.lang.System.currentTimeMillis() / 1000);

        // JWS Header
        var header;
        if (publicKey) {
            // For device binding: include the RSA public key as a standard JWK
            // in the header so AM can extract and store it for future verification.
            // Extract modulus (n) and exponent (e) from the RSA public key.
            var kf = KeyFactory.getInstance('RSA');
            var rsaPubSpec = kf.getKeySpec(publicKey, RSAPublicKeySpec.class);
            var nBytes = rsaPubSpec.getModulus().toByteArray();
            var eBytes = rsaPubSpec.getPublicExponent().toByteArray();

            // Strip leading zero byte if present (BigInteger.toByteArray() includes sign byte)
            if (nBytes.length > 0 && nBytes[0] == 0) {
                var trimmed = Arrays.copyOfRange(nBytes, 1, nBytes.length);
                nBytes = trimmed;
            }

            var nB64 = urlEncoder.encodeToString(nBytes);
            var eB64 = urlEncoder.encodeToString(eBytes);

            header = '{"alg":"RS512","kid":"' + kid + '"'
                + ',"jwk":{"kty":"RSA","kid":"' + kid + '","n":"' + nB64 + '","e":"' + eB64 + '"}}';
        } else {
            // For signing verification: no public key needed (AM already has it)
            header = '{"alg":"RS512","kid":"' + kid + '"}';
        }

        // JWS Payload — matches ForgeRock SDK format
        var payload = '{"sub":"' + escapeJsonString(userId)
            + '","challenge":"' + escapeJsonString(challenge)
            + '","exp":' + (now + 60)
            + ',"iat":' + now
            + ',"nbf":' + now + '}';

        // Signing input: base64url(header).base64url(payload)
        var signingInput = b64urlStr(header) + '.' + b64urlStr(payload);
        var signingInputBytes = new JString(signingInput).getBytes('UTF-8');

        // RS512 = RSASSA-PKCS1-v1_5 using SHA-512
        var sig = Signature.getInstance('SHA512withRSA');
        sig.initSign(privateKey);
        sig.update(signingInputBytes);
        var signatureBytes = sig.sign();

        return signingInput + '.' + b64url(signatureBytes);
    }

    /**
     * Escape special JSON characters in a string value.
     * Handles quotes, backslashes, and control characters.
     */
    function escapeJsonString(str) {
        if (!str) return '';
        return str
            .replace(/\\/g, '\\\\')
            .replace(/"/g, '\\"')
            .replace(/\n/g, '\\n')
            .replace(/\r/g, '\\r')
            .replace(/\t/g, '\\t');
    }

    // ---- Callback parsing ----

    /**
     * Extract a named output value from a callback's output array.
     *
     * @param callback single callback object with output[] array
     * @param name     output field name to find
     * @returns the value, or null if not found
     */
    function getOutput(callback, name) {
        if (!callback || !callback.output) return null;
        for (var i = 0; i < callback.output.length; i++) {
            if (callback.output[i].name === name) {
                return callback.output[i].value;
            }
        }
        return null;
    }

    /**
     * Find a callback by type in an array of callbacks.
     *
     * @param callbacks array of AM callback objects
     * @param type      callback type string (e.g., 'DeviceBindingCallback')
     * @returns the first matching callback, or null
     */
    function findCallback(callbacks, type) {
        for (var i = 0; i < callbacks.length; i++) {
            if (callbacks[i].type === type) {
                return callbacks[i];
            }
        }
        return null;
    }

    /**
     * Find the input field name for a given input name prefix within a callback.
     * AM uses positional names like IDToken1jws, IDToken2jws, etc.
     *
     * @param callback the callback object
     * @param suffix   input name suffix (e.g., 'jws', 'deviceName', 'deviceId', 'clientError')
     * @returns the full input name, or null
     */
    function findInputName(callback, suffix) {
        if (!callback || !callback.input) return null;
        for (var i = 0; i < callback.input.length; i++) {
            var name = callback.input[i].name;
            if (name.indexOf(suffix) >= 0) {
                return name;
            }
        }
        return null;
    }

    /**
     * Parse a DeviceBindingCallback from PingAM's callback array.
     *
     * DeviceBindingCallback outputs:
     *   userId, username, authenticationType, challenge, title, subtitle, description, timeout
     * DeviceBindingCallback inputs:
     *   IDToken<N>jws, IDToken<N>deviceName, IDToken<N>deviceId, IDToken<N>clientError
     *
     * @param callbacks array of AM callback objects
     * @returns { challenge, userId, username, authenticationType, timeout,
     *            inputJws, inputDeviceName, inputDeviceId, inputClientError,
     *            callbackIndex }
     */
    function parseBindingCallback(callbacks) {
        var cb = findCallback(callbacks, 'DeviceBindingCallback');
        if (!cb) throw 'No DeviceBindingCallback found in callbacks';

        var idx = -1;
        for (var i = 0; i < callbacks.length; i++) {
            if (callbacks[i] === cb) { idx = i; break; }
        }

        return {
            challenge: getOutput(cb, 'challenge'),
            userId: getOutput(cb, 'userId'),
            username: getOutput(cb, 'username'),
            authenticationType: getOutput(cb, 'authenticationType'),
            timeout: getOutput(cb, 'timeout'),
            inputJws: findInputName(cb, 'jws'),
            inputDeviceName: findInputName(cb, 'deviceName'),
            inputDeviceId: findInputName(cb, 'deviceId'),
            inputClientError: findInputName(cb, 'clientError'),
            callbackIndex: idx
        };
    }

    /**
     * Parse a DeviceSigningVerifierCallback from PingAM's callback array.
     *
     * DeviceSigningVerifierCallback outputs:
     *   userId, challenge, title, subtitle, description, timeout
     * DeviceSigningVerifierCallback inputs:
     *   IDToken<N>jws, IDToken<N>clientError
     *
     * @param callbacks array of AM callback objects
     * @returns { challenge, userId, timeout, inputJws, inputClientError, callbackIndex }
     */
    function parseSigningCallback(callbacks) {
        var cb = findCallback(callbacks, 'DeviceSigningVerifierCallback');
        if (!cb) throw 'No DeviceSigningVerifierCallback found in callbacks';

        var idx = -1;
        for (var i = 0; i < callbacks.length; i++) {
            if (callbacks[i] === cb) { idx = i; break; }
        }

        return {
            challenge: getOutput(cb, 'challenge'),
            userId: getOutput(cb, 'userId'),
            timeout: getOutput(cb, 'timeout'),
            inputJws: findInputName(cb, 'jws'),
            inputClientError: findInputName(cb, 'clientError'),
            callbackIndex: idx
        };
    }

    // ---- High-level API ----

    /**
     * Build a complete Device Binding response: generate key pair, sign JWS,
     * and set the callback input fields.
     *
     * This is the main entry point for device binding registration.
     *
     * @param callbacks raw AM callback array (will be mutated with response values)
     * @returns { keyPair: { publicKey, privateKey, kid }, jws: string, parsed: object }
     */
    function bind(callbacks) {
        var parsed = parseBindingCallback(callbacks);
        var kp = generateKeyPair();
        // Pass publicKey so it's included in the JWS header for AM to store
        var jws = buildJws(parsed.challenge, parsed.userId, kp.privateKey, kp.kid, kp.publicKey);

        // Set input values on the DeviceBindingCallback
        for (var i = 0; i < callbacks.length; i++) {
            if (callbacks[i].type === 'DeviceBindingCallback') {
                var cb = callbacks[i];
                for (var j = 0; j < cb.input.length; j++) {
                    var inputName = cb.input[j].name;
                    if (inputName === parsed.inputJws) {
                        cb.input[j].value = jws;
                    } else if (inputName === parsed.inputDeviceName) {
                        cb.input[j].value = 'Karate E2E Test Device';
                    } else if (inputName === parsed.inputDeviceId) {
                        cb.input[j].value = kp.kid;
                    }
                    // clientError left empty (success case)
                }
                break;
            }
        }

        return {
            keyPair: kp,
            jws: jws,
            parsed: parsed
        };
    }

    /**
     * Build a complete Device Signing Verifier response: sign JWS with an
     * existing private key and set the callback input fields.
     *
     * This is the main entry point for device signing verification.
     *
     * @param callbacks  raw AM callback array (will be mutated with response values)
     * @param privateKey java.security.PrivateKey from a previous bind() call
     * @param kid        key identifier from a previous bind() call
     * @returns { jws: string, parsed: object }
     */
    function sign(callbacks, privateKey, kid) {
        var parsed = parseSigningCallback(callbacks);
        var jws = buildJws(parsed.challenge, parsed.userId, privateKey, kid);

        // Set input values on the DeviceSigningVerifierCallback
        for (var i = 0; i < callbacks.length; i++) {
            if (callbacks[i].type === 'DeviceSigningVerifierCallback') {
                var cb = callbacks[i];
                for (var j = 0; j < cb.input.length; j++) {
                    var inputName = cb.input[j].name;
                    if (inputName === parsed.inputJws) {
                        cb.input[j].value = jws;
                    }
                    // clientError left empty (success case)
                }
                break;
            }
        }

        return {
            jws: jws,
            parsed: parsed
        };
    }

    /**
     * Delete all bound devices for a user via the AM Admin REST API.
     *
     * Queries the user's bound devices and deletes each one.
     * Requires an admin session token.
     *
     * Usage from Karate (called inline, not as a standalone helper):
     *   * deviceBinding.cleanupBoundDevices(amDirectUrl, amHostHeader, adminToken, 'user.1')
     *
     * Note: This function performs HTTP calls using karate.call() with a
     * reusable delete-bound-device.feature. For E2E tests, prefer using
     * the Karate-native HTTP calls directly in the .feature file for
     * better readability and error reporting.
     *
     * @param amUrl       AM base URL (e.g., 'http://127.0.0.1:28080/am')
     * @param hostHeader  AM Host header value
     * @param adminToken  iplanetDirectoryPro admin session token
     * @param username    username whose devices to clean
     * @returns number of devices deleted
     */
    function cleanupBoundDevices(amUrl, hostHeader, adminToken, username) {
        // Query bound devices
        var listResult = karate.call('helpers/list-bound-devices.feature', {
            amDirectUrl: amUrl,
            amHostHeader: hostHeader,
            adminToken: adminToken,
            user: username
        });

        var devices = listResult.devices || [];
        var count = 0;

        for (var i = 0; i < devices.length; i++) {
            karate.call('helpers/delete-bound-device.feature', {
                amDirectUrl: amUrl,
                amHostHeader: hostHeader,
                adminToken: adminToken,
                user: username,
                uuid: devices[i].uuid
            });
            count++;
        }

        return count;
    }

    // ---- Self-test ----

    /**
     * Verify that JWS generation produces a valid compact serialization.
     * Tests key generation, JWS structure (3 dot-separated parts), and
     * that the signature verifies against the public key.
     *
     * @throws if any check fails
     */
    function selfTest() {
        var kp = generateKeyPair();
        var challenge = 'dGVzdC1jaGFsbGVuZ2U='; // base64("test-challenge")
        var userId = 'id=testuser,ou=user,dc=example,dc=com';

        // Build JWS
        var jws = buildJws(challenge, userId, kp.privateKey, kp.kid);

        // Verify structure: 3 dot-separated base64url parts
        var parts = jws.split('.');
        if (parts.length !== 3) {
            throw 'JWS self-test FAILED: expected 3 parts, got ' + parts.length;
        }

        // Verify header
        var headerJson = new JString(Base64.getUrlDecoder().decode(parts[0]), 'UTF-8');
        if (headerJson.indexOf('"alg":"RS512"') < 0) {
            throw 'JWS self-test FAILED: header missing alg:RS512. Got: ' + headerJson;
        }
        if (headerJson.indexOf('"kid":"' + kp.kid + '"') < 0) {
            throw 'JWS self-test FAILED: header missing correct kid. Got: ' + headerJson;
        }

        // Verify payload
        var payloadJson = new JString(Base64.getUrlDecoder().decode(parts[1]), 'UTF-8');
        if (payloadJson.indexOf('"sub":"' + userId + '"') < 0) {
            throw 'JWS self-test FAILED: payload missing sub claim. Got: ' + payloadJson;
        }
        if (payloadJson.indexOf('"challenge":"' + challenge + '"') < 0) {
            throw 'JWS self-test FAILED: payload missing challenge claim. Got: ' + payloadJson;
        }

        // Verify signature with public key
        var signingInput = new JString(parts[0] + '.' + parts[1]).getBytes('UTF-8');
        var signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

        var sig = Signature.getInstance('SHA512withRSA');
        sig.initVerify(kp.publicKey);
        sig.update(signingInput);
        if (!sig.verify(signatureBytes)) {
            throw 'JWS self-test FAILED: signature verification failed';
        }

        karate.log('Device Binding self-test passed: JWS generation + RS512 verification OK');
    }

    return {
        // Key management
        generateKeyPair: generateKeyPair,

        // JWS construction
        buildJws: buildJws,

        // Callback parsing
        parseBindingCallback: parseBindingCallback,
        parseSigningCallback: parseSigningCallback,

        // High-level API (parse + sign + set inputs in one call)
        bind: bind,
        sign: sign,

        // Cleanup
        cleanupBoundDevices: cleanupBoundDevices,

        // Self-test
        selfTest: selfTest
    };
}
