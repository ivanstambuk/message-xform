package io.messagexform.pingaccess;

import com.pingidentity.pa.sdk.policy.SimplePluginConfiguration;
import com.pingidentity.pa.sdk.ui.ConfigurationType;
import com.pingidentity.pa.sdk.ui.Help;
import com.pingidentity.pa.sdk.ui.UIElement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Plugin configuration for the PingAccess Message Transform rule (FR-002-04).
 *
 * <p>
 * Configures the adapter via the PingAccess admin UI. Field annotations drive
 * both UI rendering ({@code @UIElement}, {@code @Help}) and server-side
 * validation ({@code @NotNull}, {@code @Min}, {@code @Max}).
 *
 * <p>
 * PingAccess discovers configuration fields via
 * {@code ConfigurationBuilder.from(MessageTransformConfig.class)} at plugin
 * registration time (SDK guide §7).
 */
public class MessageTransformConfig extends SimplePluginConfiguration {

    @UIElement(
            order = 10,
            type = ConfigurationType.TEXT,
            label = "Spec Directory",
            required = true,
            defaultValue = "/specs",
            help =
                    @Help(
                            title = "Spec Directory",
                            content = "Absolute path to the directory containing transform spec YAML"
                                    + " files. Must not contain '..' segments."))
    @NotNull
    private String specsDir = "/specs";

    @UIElement(
            order = 20,
            type = ConfigurationType.TEXT,
            label = "Profiles Directory",
            required = false,
            defaultValue = "/profiles",
            help =
                    @Help(
                            title = "Profiles Directory",
                            content = "Absolute path to the directory containing transform profile"
                                    + " files. Leave empty to use specs without profiles."))
    private String profilesDir = "/profiles";

    @UIElement(
            order = 30,
            type = ConfigurationType.TEXT,
            label = "Active Profile",
            required = false,
            defaultValue = "",
            help =
                    @Help(
                            title = "Active Profile",
                            content = "Name of the profile to activate. Leave empty for no profile" + " filtering."))
    private String activeProfile = "";

    @UIElement(
            order = 40,
            type = ConfigurationType.SELECT,
            label = "Error Mode",
            required = true,
            help =
                    @Help(
                            title = "Error Mode",
                            content = "PASS_THROUGH: log errors and continue with original message."
                                    + " DENY: reject the request/response with an RFC 9457"
                                    + " error."))
    @NotNull
    private ErrorMode errorMode = ErrorMode.PASS_THROUGH;

    @UIElement(
            order = 50,
            type = ConfigurationType.TEXT,
            label = "Reload Interval (s)",
            required = false,
            defaultValue = "0",
            help =
                    @Help(
                            title = "Reload Interval",
                            content = "Interval in seconds for re-reading spec/profile files from"
                                    + " disk. 0 = disabled (specs loaded only at startup)."
                                    + " Maximum 86400 (24 hours)."))
    @Min(0)
    @Max(86400)
    private int reloadIntervalSec = 0;

    @UIElement(
            order = 60,
            type = ConfigurationType.SELECT,
            label = "Schema Validation",
            required = false,
            help =
                    @Help(
                            title = "Schema Validation",
                            content = "STRICT: reject specs failing JSON Schema validation."
                                    + " LENIENT: log warnings but accept specs."))
    private SchemaValidation schemaValidation = SchemaValidation.LENIENT;

    @UIElement(
            order = 70,
            type = ConfigurationType.CHECKBOX,
            label = "Enable JMX Metrics",
            required = false,
            defaultValue = "false",
            help =
                    @Help(
                            title = "Enable JMX Metrics",
                            content = "Enable JMX MBean registration for transform metrics. When"
                                    + " enabled, counters for success/error/passthrough"
                                    + " transforms and latency are exposed via JMX under the"
                                    + " 'io.messagexform' domain. Disabled by default for"
                                    + " zero overhead."))
    private boolean enableJmxMetrics = false;

    // ---- Getters and setters ----

    public String getSpecsDir() {
        return specsDir;
    }

    public void setSpecsDir(String specsDir) {
        this.specsDir = specsDir;
    }

    public String getProfilesDir() {
        return profilesDir;
    }

    public void setProfilesDir(String profilesDir) {
        this.profilesDir = profilesDir;
    }

    public String getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(String activeProfile) {
        this.activeProfile = activeProfile;
    }

    public ErrorMode getErrorMode() {
        return errorMode;
    }

    public void setErrorMode(ErrorMode errorMode) {
        this.errorMode = errorMode;
    }

    public int getReloadIntervalSec() {
        return reloadIntervalSec;
    }

    public void setReloadIntervalSec(int reloadIntervalSec) {
        this.reloadIntervalSec = reloadIntervalSec;
    }

    public SchemaValidation getSchemaValidation() {
        return schemaValidation;
    }

    public void setSchemaValidation(SchemaValidation schemaValidation) {
        this.schemaValidation = schemaValidation;
    }

    public boolean getEnableJmxMetrics() {
        return enableJmxMetrics;
    }

    public void setEnableJmxMetrics(boolean enableJmxMetrics) {
        this.enableJmxMetrics = enableJmxMetrics;
    }
}
