// Generate PA Admin API Basic auth header
function fn() {
    var user = karate.get('paUser');
    var pass = karate.get('paPassword');
    var Base64 = Java.type('java.util.Base64');
    var encoded = Base64.getEncoder().encodeToString(new java.lang.String(user + ':' + pass).getBytes());
    return 'Basic ' + encoded;
}
