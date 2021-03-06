package asura.app.api.auth

import java.time.{Duration, LocalDate, ZoneId}
import java.util.Date

import org.ldaptive._
import org.ldaptive.auth.{Authenticator, BindAuthenticationHandler, SearchDnResolver}
import org.pac4j.core.context.WebContext
import org.pac4j.core.credentials.UsernamePasswordCredentials
import org.pac4j.core.profile.CommonProfile
import org.pac4j.jwt.config.signature.SecretSignatureConfiguration
import org.pac4j.jwt.profile.JwtGenerator
import org.pac4j.ldap.profile.service.LdapProfileService
import play.api.Configuration

object LdapAuthenticator {

  def apply(configuration: Configuration): LdapProfileService = {
    val connConfig = new ConnectionConfig()
    connConfig.setConnectTimeout(Duration.ofMillis(configuration.get[Long]("asura.ldap.connectionTimeout")))
    connConfig.setResponseTimeout(Duration.ofMillis(configuration.get[Long]("asura.ldap.responseTimeout")))
    connConfig.setLdapUrl(configuration.get[String]("asura.ldap.url"))
    connConfig.setConnectionInitializer(new BindConnectionInitializer(configuration.get[String]("asura.ldap.bindDn"), new Credential(configuration.get[String]("asura.ldap.password"))))
    val connFactory = new DefaultConnectionFactory(connConfig)
    val handler = new BindAuthenticationHandler(connFactory)
    val dnResolver = new SearchDnResolver(connFactory)
    dnResolver.setBaseDn(configuration.get[String]("asura.ldap.searchbase"))
    dnResolver.setSubtreeSearch(true)
    dnResolver.setUserFilter(configuration.get[String]("asura.ldap.userFilter"))
    val authenticator = new Authenticator()
    authenticator.setDnResolver(dnResolver)
    authenticator.setAuthenticationHandler(handler)
    new CustomLdapProfileService(configuration, connFactory, authenticator, configuration.get[String]("asura.ldap.searchbase"))
  }

  class CustomLdapProfileService(
                                  configuration: Configuration,
                                  connectionFactory: ConnectionFactory,
                                  authenticator: Authenticator,
                                  usersDn: String)
    extends LdapProfileService(connectionFactory, authenticator, usersDn) {

    this.setIdAttribute(configuration.get[String]("asura.ldap.userIdAttr"))
    this.setAttributes(s"${configuration.get[String]("asura.ldap.userRealNameAttr")},${configuration.get[String]("asura.ldap.userEmailAttr")}")

    override def validate(credentials: UsernamePasswordCredentials, context: WebContext): Unit = {
      super.validate(credentials, context)
      val jwtGenerator = new JwtGenerator[CommonProfile](new SecretSignatureConfiguration(configuration.get[String]("asura.jwt.secret")))
      val tomorrow = LocalDate.now().plusDays(1).atStartOfDay().plusHours(3)
      jwtGenerator.setExpirationTime(Date.from(tomorrow.atZone(ZoneId.systemDefault()).toInstant()))
      val profile = credentials.getUserProfile
      val token = jwtGenerator.generate(profile)
      profile.addAttribute("token", token)
    }
  }

}
