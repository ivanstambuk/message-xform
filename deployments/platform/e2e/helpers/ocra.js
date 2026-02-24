/**
 * OCRA (RFC 6287) Helper for Karate E2E Tests
 *
 * General-purpose OATH Challenge-Response Algorithm implementation using pure
 * JDK crypto. No external dependencies required.
 *
 * Implements the full OCRA algorithm as specified in RFC 6287 and
 * draft-mraihi-mutual-oath-hotp-variants. Supports all data input combinations:
 *   C  — counter (8 bytes)
 *   Q  — question/challenge (N=numeric, A=alphanumeric, H=hex; up to 128 bytes)
 *   P  — PIN hash (SHA1/SHA256/SHA512)
 *   S  — session information (64/128/256/512 bytes)
 *   T  — timestamp (8 bytes)
 *
 * Usage from Karate:
 *   * def ocra = call read('helpers/ocra.js')
 *   * def otp = ocra.generate('OCRA-1:HOTP-SHA256-6:C-QH64', secretHex, { counter: 0, question: 'AABB...' })
 *   * ocra.selfTest()
 *
 * Reference implementation: openauth-sim core-ocra module (OcraResponseCalculator.java)
 * Test vectors: draft-mraihi-mutual-oath-hotp-variants Appendix B
 */
function fn() {
    var Mac = Java.type('javax.crypto.Mac');
    var SecretKeySpec = Java.type('javax.crypto.spec.SecretKeySpec');
    var BigInteger = Java.type('java.math.BigInteger');
    var JString = Java.type('java.lang.String');

    // ---- Low-level helpers ----

    /**
     * Convert a hex string to a Java byte[].
     * Uses the BigInteger trick from the RFC reference implementation:
     * prepend "10" to avoid sign issues, then strip the leading byte.
     */
    function hexToBytes(hex) {
        var bArray = new BigInteger('10' + hex, 16).toByteArray();
        var ret = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, bArray.length - 1);
        java.lang.System.arraycopy(bArray, 1, ret, 0, ret.length);
        return ret;
    }

    /**
     * Compute HMAC with the specified algorithm.
     * @param crypto  JDK algorithm name: 'HmacSHA1', 'HmacSHA256', 'HmacSHA512'
     * @param keyBytes  HMAC key as byte[]
     * @param message   message as byte[]
     * @returns byte[]
     */
    function hmac(crypto, keyBytes, message) {
        var mac = Mac.getInstance(crypto);
        mac.init(new SecretKeySpec(keyBytes, 'RAW'));
        return mac.doFinal(message);
    }

    /**
     * Dynamic truncation: extract N decimal digits from HMAC output.
     * Standard OATH truncation per RFC 4226 §5.3.
     */
    function truncate(hash, digits) {
        var offset = hash[hash.length - 1] & 0x0F;
        var binary = ((hash[offset] & 0x7F) << 24)
            | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8)
            | (hash[offset + 3] & 0xFF);

        // Use BigInteger for modulus to avoid JS 32-bit integer overflow
        var bi = new BigInteger('' + (binary >>> 0));
        var modulus = new BigInteger('1');
        var ten = new BigInteger('10');
        for (var i = 0; i < digits; i++) {
            modulus = modulus.multiply(ten);
        }
        var otp = bi.mod(modulus).toString();
        while (otp.length < digits) {
            otp = '0' + otp;
        }
        return otp;
    }

    /**
     * Left-pad a hex string with zeros to reach the target length.
     */
    function padLeft(hex, targetLength) {
        while (hex.length < targetLength) {
            hex = '0' + hex;
        }
        return hex;
    }

    /**
     * Right-pad a hex string with zeros to reach the target length.
     */
    function padRight(hex, targetLength) {
        while (hex.length < targetLength) {
            hex = hex + '0';
        }
        return hex;
    }

    // ---- Suite parsing ----

    /**
     * Parse an OCRA suite string into its components.
     * Format: OCRA-1:<cryptoFunction>:<dataInput>
     *
     * @param suite  e.g. 'OCRA-1:HOTP-SHA256-6:C-QH64'
     * @returns { crypto, digits, hasCounter, question, password, session, timestamp }
     */
    function parseSuite(suite) {
        var parts = suite.split(':');
        if (parts.length !== 3 || parts[0].toUpperCase() !== 'OCRA-1') {
            throw 'Invalid OCRA suite: ' + suite;
        }

        var cryptoFunction = parts[1];
        var dataInput = parts[2];

        // Determine HMAC algorithm
        var crypto;
        var cfLower = cryptoFunction.toLowerCase();
        if (cfLower.indexOf('sha512') > -1) {
            crypto = 'HmacSHA512';
        } else if (cfLower.indexOf('sha256') > -1) {
            crypto = 'HmacSHA256';
        } else {
            crypto = 'HmacSHA1';
        }

        // Extract digit count (last number in crypto function segment)
        var lastDash = cryptoFunction.lastIndexOf('-');
        var digits = parseInt(cryptoFunction.substring(lastDash + 1), 10);

        // Parse data input tokens
        var diLower = dataInput.toLowerCase();
        var hasCounter = diLower.indexOf('c') > -1 && (diLower.startsWith('c') || diLower.indexOf('-c') > -1);

        // Check for counter: must be standalone 'C' token, not part of QC/SC/etc.
        hasCounter = false;
        var tokens = dataInput.split('-');
        var question = null;   // { format, length }
        var password = null;   // { algorithm, lengthBytes }
        var session = null;    // { lengthBytes }
        var timestamp = false;

        for (var t = 0; t < tokens.length; t++) {
            var token = tokens[t].trim();
            if (token === '') continue;
            var tokenUpper = token.toUpperCase();

            if (tokenUpper === 'C') {
                hasCounter = true;
            } else if (tokenUpper.charAt(0) === 'Q' && token.length >= 3) {
                var qFormat = tokenUpper.charAt(1); // N, A, H
                var qLength = parseInt(token.substring(2), 10);
                question = { format: qFormat, length: qLength };
            } else if (tokenUpper.charAt(0) === 'P') {
                var pAlg = tokenUpper.substring(1);
                var pLen;
                if (pAlg === 'SHA256') pLen = 32;
                else if (pAlg === 'SHA512') pLen = 64;
                else pLen = 20; // SHA1 default
                password = { algorithm: pAlg, lengthBytes: pLen };
            } else if (tokenUpper.charAt(0) === 'S') {
                var sLen = token.length > 1 ? parseInt(token.substring(1), 10) : 64;
                session = { lengthBytes: sLen };
            } else if (tokenUpper.charAt(0) === 'T') {
                timestamp = true;
            }
        }

        return {
            crypto: crypto,
            digits: digits,
            hasCounter: hasCounter,
            question: question,
            password: password,
            session: session,
            timestamp: timestamp
        };
    }

    // ---- Core OCRA computation ----

    /**
     * Generate an OCRA response.
     *
     * This is a faithful port of the RFC reference implementation (OCRA.generateOCRA)
     * and the openauth-sim OcraResponseCalculator.computeReferenceOcra().
     *
     * @param ocraSuite  full suite string, e.g. 'OCRA-1:HOTP-SHA256-6:C-QH64'
     * @param keyHex     shared secret as hex string
     * @param params     object with optional fields:
     *   - counter:      counter value (number), required if suite has 'C'
     *   - question:     challenge question as hex string, required if suite has 'Q'
     *   - password:     PIN hash as hex string, required if suite has 'P'
     *   - session:      session information as hex string, required if suite has 'S'
     *   - timestamp:    timestamp as hex string, required if suite has 'T'
     * @returns {string} OTP as zero-padded decimal string
     */
    function generate(ocraSuite, keyHex, params) {
        if (!ocraSuite) throw 'ocraSuite is required';
        if (!keyHex) throw 'keyHex (shared secret) is required';
        if (!params) params = {};

        var parsed = parseSuite(ocraSuite);

        // Prepare each data input component as hex strings with RFC-mandated padding
        var counterHex = '';
        if (parsed.hasCounter) {
            if (params.counter === undefined || params.counter === null) {
                throw 'counter is required for suite: ' + ocraSuite;
            }
            counterHex = new BigInteger('' + params.counter).toString(16).toUpperCase();
            counterHex = padLeft(counterHex, 16); // 8 bytes = 16 hex chars
        }

        var questionHex = '';
        if (parsed.question) {
            var rawQ = params.question;
            if (!rawQ && rawQ !== '') {
                throw 'question is required for suite: ' + ocraSuite;
            }
            // Convert based on format
            if (parsed.question.format === 'N') {
                // Numeric: decimal → hex
                questionHex = new BigInteger(rawQ, 10).toString(16).toUpperCase();
            } else if (parsed.question.format === 'H') {
                // Hex: use as-is
                questionHex = rawQ.toUpperCase();
            } else {
                // Alphanumeric/Character: ASCII → hex
                var asciiBytes = new JString(rawQ.toUpperCase()).getBytes('US-ASCII');
                var sb = new java.lang.StringBuilder();
                for (var i = 0; i < asciiBytes.length; i++) {
                    var byteVal = asciiBytes[i] & 0xFF;
                    if (byteVal < 16) sb.append('0');
                    sb.append(java.lang.Integer.toHexString(byteVal).toUpperCase());
                }
                questionHex = sb.toString();
            }
            questionHex = padRight(questionHex, 256); // 128 bytes = 256 hex chars
        }

        var passwordHex = '';
        if (parsed.password) {
            if (!params.password) {
                throw 'password (PIN hash) is required for suite: ' + ocraSuite;
            }
            passwordHex = params.password.toUpperCase();
            passwordHex = padLeft(passwordHex, parsed.password.lengthBytes * 2);
        }

        var sessionHex = '';
        if (parsed.session) {
            if (!params.session) {
                throw 'session information is required for suite: ' + ocraSuite;
            }
            sessionHex = params.session.toUpperCase();
            sessionHex = padLeft(sessionHex, parsed.session.lengthBytes * 2);
        }

        var timestampHex = '';
        if (parsed.timestamp) {
            if (!params.timestamp) {
                throw 'timestamp is required for suite: ' + ocraSuite;
            }
            timestampHex = params.timestamp.toUpperCase();
            timestampHex = padLeft(timestampHex, 16); // 8 bytes = 16 hex chars
        }

        // Build the message: suiteBytes || 0x00 || counter || question || password || session || timestamp
        var suiteBytes = new JString(ocraSuite).getBytes('US-ASCII');
        var counterBytes = counterHex.length > 0 ? hexToBytes(counterHex) : null;
        var questionBytes = questionHex.length > 0 ? hexToBytes(questionHex) : null;
        var passwordBytes = passwordHex.length > 0 ? hexToBytes(passwordHex) : null;
        var sessionBytes = sessionHex.length > 0 ? hexToBytes(sessionHex) : null;
        var timestampBytes = timestampHex.length > 0 ? hexToBytes(timestampHex) : null;

        var msgLen = suiteBytes.length + 1; // +1 for 0x00 delimiter
        if (counterBytes) msgLen += counterBytes.length;
        if (questionBytes) msgLen += questionBytes.length;
        if (passwordBytes) msgLen += passwordBytes.length;
        if (sessionBytes) msgLen += sessionBytes.length;
        if (timestampBytes) msgLen += timestampBytes.length;

        var msg = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, msgLen);
        java.lang.System.arraycopy(suiteBytes, 0, msg, 0, suiteBytes.length);
        msg[suiteBytes.length] = 0x00; // delimiter

        var offset = suiteBytes.length + 1;
        if (counterBytes) {
            java.lang.System.arraycopy(counterBytes, 0, msg, offset, counterBytes.length);
            offset += counterBytes.length;
        }
        if (questionBytes) {
            java.lang.System.arraycopy(questionBytes, 0, msg, offset, questionBytes.length);
            offset += questionBytes.length;
        }
        if (passwordBytes) {
            java.lang.System.arraycopy(passwordBytes, 0, msg, offset, passwordBytes.length);
            offset += passwordBytes.length;
        }
        if (sessionBytes) {
            java.lang.System.arraycopy(sessionBytes, 0, msg, offset, sessionBytes.length);
            offset += sessionBytes.length;
        }
        if (timestampBytes) {
            java.lang.System.arraycopy(timestampBytes, 0, msg, offset, timestampBytes.length);
        }

        var hash = hmac(parsed.crypto, hexToBytes(keyHex), msg);
        return truncate(hash, parsed.digits);
    }

    // ---- Self-test ----

    /**
     * Run self-test using known test vectors from draft-mraihi-mutual-oath-hotp-variants.
     * Suite: OCRA-1:HOTP-SHA256-6:C-QH64
     *
     * Throws on any mismatch.
     */
    function selfTest() {
        var suite = 'OCRA-1:HOTP-SHA256-6:C-QH64';
        var key = '3132333435363738393031323334353637383930313233343536373839303132';
        var challenge = '00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF';

        var vectors = [
            { counter: 0, expected: '213703' },
            { counter: 1, expected: '429968' },
            { counter: 2, expected: '053393' }
        ];

        for (var i = 0; i < vectors.length; i++) {
            var v = vectors[i];
            var result = generate(suite, key, { counter: v.counter, question: challenge });
            if (result !== v.expected) {
                throw 'OCRA self-test FAILED for counter=' + v.counter
                + ': expected ' + v.expected + ', got ' + result;
            }
        }
        karate.log('OCRA self-test passed: 3/3 vectors verified for ' + suite);
    }

    return {
        generate: generate,
        parseSuite: parseSuite,
        selfTest: selfTest
    };
}
