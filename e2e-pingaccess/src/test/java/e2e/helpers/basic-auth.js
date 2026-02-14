function() {
    var config = karate.get('paUser') + ':' + karate.get('paPassword');
    return 'Basic ' + java.util.Base64.getEncoder().encodeToString(config.getBytes());
}
