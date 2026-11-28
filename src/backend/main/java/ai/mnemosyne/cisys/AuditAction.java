/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

/**
 * The actions recorded in the audit log. The code is stored in the database and used by the admin
 * API and UI, so it is part of the public contract; rename an action only together with a data
 * migration.
 */
public enum AuditAction {
  PROJECT_CREATE("project.create"),
  PROJECT_UPDATE("project.update"),
  PROJECT_CREDENTIAL_UPDATE("project.credential.update"),
  PROJECT_DELETE("project.delete"),
  PROJECT_MEMBER_ADD("project.member.add"),
  PROJECT_MEMBER_ROLE("project.member.role"),
  PROJECT_MEMBER_REMOVE("project.member.remove"),
  PROJECT_TEAM_UPDATE("project.team.update"),
  TEAM_CREATE("team.create"),
  TEAM_UPDATE("team.update"),
  TEAM_DELETE("team.delete"),
  TEAM_MEMBER_ADD("team.member.add"),
  TEAM_MEMBER_ROLE("team.member.role"),
  TEAM_MEMBER_REMOVE("team.member.remove"),
  TEAM_TOKEN_UPDATE("team.token.update"),
  PROJECT_WEBHOOK_CREATE("project.webhook.create"),
  PROJECT_WEBHOOK_UPDATE("project.webhook.update"),
  PROJECT_WEBHOOK_SECRET_ROTATE("project.webhook.secret.rotate"),
  PROJECT_WEBHOOK_DELETE("project.webhook.delete"),
  USER_CREATE("user.create"),
  USER_UPDATE("user.update"),
  USER_PASSWORD_RESET("user.password.reset"),
  USER_DELETE("user.delete"),
  SETTINGS_GENERAL_UPDATE("settings.general.update"),
  SETTINGS_RETENTION_UPDATE("settings.retention.update"),
  SETTINGS_BUILDS_UPDATE("settings.builds.update"),
  SETTINGS_CHECKOUT_UPDATE("settings.checkout.update"),
  SETTINGS_ARTIFACTS_UPDATE("settings.artifacts.update"),
  SETTINGS_PERFORMANCE_UPDATE("settings.performance.update"),
  SETTINGS_POLLING_UPDATE("settings.polling.update"),
  SETTINGS_PULL_REQUESTS_UPDATE("settings.pullrequests.update"),
  BRANDING_UPDATE("branding.update"),
  IMAGE_ALLOWLIST_UPDATE("image.allowlist.update"),
  IMAGE_PULL("image.pull"),
  IMAGE_REMOVE("image.remove"),
  NETWORK_HOST_CREATE("network.host.create"),
  NETWORK_HOST_UPDATE("network.host.update"),
  NETWORK_HOST_DELETE("network.host.delete"),
  NETWORK_TEMPLATE_ADD("network.template.add"),
  REPORT_PLUGIN_SET("report.plugin.set"),
  REPORT_PLUGIN_DELETE("report.plugin.delete"),
  ARTICLE_CREATE("article.create"),
  ARTICLE_UPDATE("article.update"),
  ARTICLE_DELETE("article.delete"),
  BUILD_START("build.start"),
  BUILD_RERUN("build.rerun"),
  BUILD_PIN("build.pin"),
  BUILD_UNPIN("build.unpin"),
  BUILD_CANCEL("build.cancel");

  private final String code;

  AuditAction(String code) {
    this.code = code;
  }

  /** The stable code stored in the audit log. */
  public String code() {
    return code;
  }
}
