/**
 * WebAuthn FIDO2 Helper for Karate E2E Tests
 *
 * Generates registration (attestation) and authentication (assertion) payloads
 * using pure JDK crypto. No external dependencies required.
 *
 * Usage from Karate:
 *   * def webauthn = call read('helpers/webauthn.js')
 *   * def regResult = webauthn.register(challengeBytes, rpId, origin)
 *   * def authResult = webauthn.authenticate(challengeBytes, rpId, origin, privateKey, credIdB64, userName)
 */
function fn() {
    var KeyPairGenerator = Java.type('java.security.KeyPairGenerator');
    var ECGenParameterSpec = Java.type('java.security.spec.ECGenParameterSpec');
    var Signature = Java.type('java.security.Signature');
    var MessageDigest = Java.type('java.security.MessageDigest');
    var Base64 = Java.type('java.util.Base64');
    var ByteArrayOutputStream = Java.type('java.io.ByteArrayOutputStream');
    var SecureRandom = Java.type('java.security.SecureRandom');
    var JString = Java.type('java.lang.String');
    var JByte = Java.type('java.lang.Byte');
    var JArray = Java.type('java.lang.reflect.Array');
    var JSystem = Java.type('java.lang.System');

    var urlEncoder = Base64.getUrlEncoder().withoutPadding();
    var random = new SecureRandom();

    // ---- Low-level helpers ----

    function newByteArray(size) {
        return JArray.newInstance(JByte.TYPE, size);
    }

    /** Convert a BigInteger to a 32-byte unsigned big-endian array. */
    function bigIntToBytes32(bigInt) {
        var raw = bigInt.toByteArray();
        var result = newByteArray(32);
        var start = 0;
        while (start < raw.length && raw[start] == 0 && (raw.length - start) > 32) start++;
        var len = Math.min(raw.length - start, 32);
        JSystem.arraycopy(raw, start, result, 32 - len, len);
        return result;
    }

    /** Convert Java byte[] to Int8Array-style comma-separated signed bytes (as JS would). */
    function toInt8String(bytes) {
        var parts = [];
        for (var i = 0; i < bytes.length; i++) {
            parts.push('' + bytes[i]); // Java bytes are already signed (-128..127)
        }
        return parts.join(',');
    }

    /** Parse Int8Array notation [n,n,n,...] from JS text into Java byte[]. */
    function parseIntArray(text) {
        var parts = text.split(',');
        var result = newByteArray(parts.length);
        for (var i = 0; i < parts.length; i++) {
            result[i] = JByte.parseByte(parts[i].trim());
        }
        return result;
    }

    // ---- CBOR encoding helpers ----

    function writeCborMajor(baos, majorType, length) {
        var mt = majorType << 5;
        if (length < 24) {
            baos.write(mt | length);
        } else if (length < 256) {
            baos.write(mt | 24);
            baos.write(length);
        } else if (length < 65536) {
            baos.write(mt | 25);
            baos.write((length >> 8) & 0xFF);
            baos.write(length & 0xFF);
        }
    }

    function writeCborString(baos, str) {
        var bytes = new JString(str).getBytes('UTF-8');
        writeCborMajor(baos, 3, bytes.length);
        baos.write(bytes, 0, bytes.length);
    }

    function writeCborBytes(baos, bytes) {
        writeCborMajor(baos, 2, bytes.length);
        baos.write(bytes, 0, bytes.length);
    }

    function writeCborUint(baos, value) {
        writeCborMajor(baos, 0, value);
    }

    function writeCborNegint(baos, value) {
        // CBOR negative: major type 1, additional = -1 - value
        writeCborMajor(baos, 1, -1 - value);
    }

    // ---- WebAuthn data builders ----

    /** Build clientDataJSON as UTF-8 bytes. */
    function buildClientDataJson(challengeBytes, origin, type) {
        var challengeB64 = urlEncoder.encodeToString(challengeBytes);
        var json = '{"type":"' + type + '","challenge":"' + challengeB64 +
            '","origin":"' + origin + '","crossOrigin":false}';
        return new JString(json).getBytes('UTF-8');
    }

    /** Build COSE EC2 P-256 public key map. */
    function buildCoseKey(xBytes, yBytes) {
        var baos = new ByteArrayOutputStream();
        baos.write(0xA5); // map(5)
        writeCborUint(baos, 1); writeCborUint(baos, 2);      // kty: EC2
        writeCborUint(baos, 3); writeCborNegint(baos, -7);    // alg: ES256
        writeCborNegint(baos, -1); writeCborUint(baos, 1);    // crv: P-256
        writeCborNegint(baos, -2); writeCborBytes(baos, xBytes); // x
        writeCborNegint(baos, -3); writeCborBytes(baos, yBytes); // y
        return baos.toByteArray();
    }

    /** Build authenticatorData for registration (with attested credential data). */
    function buildRegAuthData(rpId, publicKey, credentialId) {
        var md = MessageDigest.getInstance('SHA-256');
        var rpIdHash = md.digest(new JString(rpId).getBytes('UTF-8'));

        var baos = new ByteArrayOutputStream();
        baos.write(rpIdHash, 0, rpIdHash.length);
        baos.write(0x41); // flags: UP=1, AT=1 (0x01 | 0x40)
        // signCount = 0 (4 bytes big-endian)
        baos.write(0); baos.write(0); baos.write(0); baos.write(0);
        // AAGUID (16 zero bytes = no attestation)
        for (var i = 0; i < 16; i++) baos.write(0);
        // credentialId length (2 bytes big-endian)
        baos.write((credentialId.length >> 8) & 0xFF);
        baos.write(credentialId.length & 0xFF);
        // credentialId
        baos.write(credentialId, 0, credentialId.length);
        // COSE public key
        var point = publicKey.getW();
        var xBytes = bigIntToBytes32(point.getAffineX());
        var yBytes = bigIntToBytes32(point.getAffineY());
        var coseKey = buildCoseKey(xBytes, yBytes);
        baos.write(coseKey, 0, coseKey.length);

        return baos.toByteArray();
    }

    /** Build authenticatorData for assertion (no attested credential data). */
    function buildAssertionAuthData(rpId, signCount) {
        var md = MessageDigest.getInstance('SHA-256');
        var rpIdHash = md.digest(new JString(rpId).getBytes('UTF-8'));

        var baos = new ByteArrayOutputStream();
        baos.write(rpIdHash, 0, rpIdHash.length);
        baos.write(0x01); // flags: UP=1 only
        baos.write((signCount >> 24) & 0xFF);
        baos.write((signCount >> 16) & 0xFF);
        baos.write((signCount >> 8) & 0xFF);
        baos.write(signCount & 0xFF);

        return baos.toByteArray();
    }

    /** Build 'none' attestation object (CBOR). */
    function buildAttestationObject(authData) {
        var baos = new ByteArrayOutputStream();
        baos.write(0xA3); // map(3)
        writeCborString(baos, 'fmt'); writeCborString(baos, 'none');
        writeCborString(baos, 'attStmt'); baos.write(0xA0); // empty map
        writeCborString(baos, 'authData'); writeCborBytes(baos, authData);
        return baos.toByteArray();
    }

    /** Sign assertion: SHA256withECDSA over (authenticatorData || SHA-256(clientDataJSON)). */
    function signAssertion(privateKey, authenticatorData, clientDataJson) {
        var md = MessageDigest.getInstance('SHA-256');
        var clientDataHash = md.digest(clientDataJson);

        var signedData = new ByteArrayOutputStream();
        signedData.write(authenticatorData, 0, authenticatorData.length);
        signedData.write(clientDataHash, 0, clientDataHash.length);

        var sig = Signature.getInstance('SHA256withECDSA');
        sig.initSign(privateKey);
        sig.update(signedData.toByteArray());
        return sig.sign();
    }

    // ---- Callback parsing ----

    /**
     * Parse WebAuthn challenge and metadata from PingAM callbacks.
     *
     * @param callbacks - array of AM callback objects
     * @returns { challenge: byte[], rpId: string, isRegistration: boolean, credentialIds: string[] }
     */
    function parseCallbacks(callbacks) {
        var script = null;
        var hiddenInputName = null;

        for (var i = 0; i < callbacks.length; i++) {
            var cb = callbacks[i];
            if (cb.type === 'TextOutputCallback') {
                var msg = cb.output[0].value;
                if (msg.indexOf('navigator.credentials.create') >= 0 ||
                    msg.indexOf('navigator.credentials.get') >= 0) {
                    script = msg;
                }
            }
            if (cb.type === 'HiddenValueCallback') {
                hiddenInputName = cb.input[0].name;
            }
        }
        if (!script) throw 'No WebAuthn TextOutputCallback found';

        // Parse challenge: new Int8Array([...]).buffer
        var cm = script.match(/challenge:\s*new\s+Int8Array\(\[([^\]]+)\]\)/);
        if (!cm) throw 'Could not parse challenge from script';
        var challenge = parseIntArray(cm[1]);

        // Detect registration vs authentication
        var isReg = script.indexOf('navigator.credentials.create') >= 0;

        // Parse rpId from rp: { ... id: "..." }
        var rpMatch = script.match(/rp:\s*\{[^}]*id:\s*"([^"]+)"/);
        var rpId = rpMatch ? rpMatch[1] : 'localhost';

        // Parse allowCredentials credential IDs (for authentication)
        var credIds = [];
        if (!isReg) {
            // Find text after 'allowCredentials:' and before the options block delimiter '};'
            var acIdx = script.indexOf('allowCredentials:');
            if (acIdx >= 0) {
                var acSection = script.substring(acIdx, script.indexOf('};', acIdx) + 2);
                // Extract all Int8Array([...]) patterns within the allowCredentials section
                var idRegex = /Int8Array\(\[([^\]]+)\]\)/g;
                var idMatch;
                while ((idMatch = idRegex.exec(acSection)) !== null) {
                    var bytes = parseIntArray(idMatch[1]);
                    credIds.push(urlEncoder.encodeToString(bytes));
                }
            }
        }

        return {
            challenge: challenge,
            rpId: rpId,
            isRegistration: isReg,
            credentialIds: credIds,
            hiddenInputName: hiddenInputName || 'IDToken3'
        };
    }

    // ---- High-level API ----

    /**
     * Generate a WebAuthn registration (attestation) response.
     *
     * Returns the outcome string to submit in HiddenValueCallback,
     * plus the private key and credential ID for later authentication.
     */
    function register(challengeBytes, rpId, origin) {
        var kpg = KeyPairGenerator.getInstance('EC');
        kpg.initialize(new ECGenParameterSpec('secp256r1'), random);
        var keyPair = kpg.generateKeyPair();

        var credentialId = newByteArray(32);
        random.nextBytes(credentialId);
        var credIdB64 = urlEncoder.encodeToString(credentialId);

        var clientDataJson = buildClientDataJson(challengeBytes, origin, 'webauthn.create');
        var clientDataStr = new JString(clientDataJson, 'UTF-8');

        var authData = buildRegAuthData(rpId, keyPair.getPublic(), credentialId);
        var attestationObject = buildAttestationObject(authData);

        // Format: { legacyData: clientData + "::" + attBytes(Int8Array) + "::" + credId, authenticatorAttachment: "platform" }
        var outcome = '{"legacyData":"' +
            clientDataStr.toString().replaceAll('"', '\\"') +
            '::' + toInt8String(attestationObject) +
            '::' + credIdB64 +
            '","authenticatorAttachment":"platform"}';

        return {
            outcome: outcome,
            privateKey: keyPair.getPrivate(),
            publicKey: keyPair.getPublic(),
            credentialIdB64: credIdB64
        };
    }

    /**
     * Generate a WebAuthn authentication (assertion) response.
     *
     * Returns the outcome string to submit in HiddenValueCallback.
     */
    function authenticate(challengeBytes, rpId, origin, privateKey, credIdB64, userName) {
        var clientDataJson = buildClientDataJson(challengeBytes, origin, 'webauthn.get');
        var clientDataStr = new JString(clientDataJson, 'UTF-8');

        var authData = buildAssertionAuthData(rpId, 1);
        var signature = signAssertion(privateKey, authData, clientDataJson);

        // Format: { legacyData: clientData + "::" + authData(Int8Array) + "::" + sig(Int8Array) + "::" + rawId + "::" + userHandle }
        var outcome = '{"legacyData":"' +
            clientDataStr.toString().replaceAll('"', '\\"') +
            '::' + toInt8String(authData) +
            '::' + toInt8String(signature) +
            '::' + credIdB64 +
            '::' + userName +
            '","authenticatorAttachment":"platform"}';

        return {
            outcome: outcome
        };
    }

    return {
        parseCallbacks: parseCallbacks,
        register: register,
        authenticate: authenticate,
        toInt8String: toInt8String,
        parseIntArray: parseIntArray
    };
}
