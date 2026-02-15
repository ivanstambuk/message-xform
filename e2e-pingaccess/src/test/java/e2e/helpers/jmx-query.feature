    @ignore
Feature: JMX query helper (Phase 10)
    # Helper feature called by jmx-metrics.feature to query a single
    # JMX MBean attribute.  Uses javax.management.remote to connect
    # to the PA JVM's MBeanServer via RMI.
    #
    # Parameters:
    #   jmxUrl       - JMX service URL (e.g. service:jmx:rmi:///jndi/rmi://localhost:19999/jmxrmi)
    #   objectName   - MBean ObjectName string (e.g. io.messagexform:type=TransformMetrics,instance=*)
    #   attributeName - attribute to read (e.g. ActiveSpecCount), or '__exists__' to check existence

  Scenario: Query JMX MBean attribute
    * def queryJmx =
      """
      function() {
        var JMXServiceURL = Java.type('javax.management.remote.JMXServiceURL');
        var JMXConnectorFactory = Java.type('javax.management.remote.JMXConnectorFactory');
        var ObjectName = Java.type('javax.management.ObjectName');

        var url = new JMXServiceURL(jmxUrl);
        var connector = JMXConnectorFactory.connect(url, null);
        try {
          var mbsc = connector.getMBeanServerConnection();
          var pattern = new ObjectName(objectName);
          var names = mbsc.queryNames(pattern, null);

          if (names.isEmpty()) {
            return { exists: false, value: null, error: null };
          }

          // Use first matching MBean
          var mbeanName = names.iterator().next();

          if (attributeName === '__exists__') {
            return { exists: true, value: null, error: null };
          }

          var value = mbsc.getAttribute(mbeanName, attributeName);
          // Convert to a simple type (Long → number, Double → number, etc.)
          return { exists: true, value: value + 0, error: null };
        } catch (e) {
          return { exists: false, value: null, error: '' + e.getMessage() };
        } finally {
          connector.close();
        }
      }
      """

    * def jmxResult = call queryJmx
