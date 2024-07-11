package com.graebert.storage.security;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.handler.RateLimitationHandler;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.AuthenticationProvider;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AuthProvider implements AuthenticationProvider {

  private static JsonObject sessionErrorMessages;
  private String sessionId;
  private String rateLimiterKey;

  public static AuthorizationProvider getAdminAuthorizationProvider() {
    Set<Authorization> set = new HashSet<>();
    Authorization auth = RoleBasedAuthorization.create("admin");
    set.add(auth);
    return AuthorizationProvider.create(Field.IS_ADMIN.getName(), set);
  }

  public static String getSessionErrorMessage(String sessionId) {
    if (sessionErrorMessages != null && sessionErrorMessages.containsKey(sessionId)) {
      return sessionErrorMessages.getString(sessionId);
    }

    return null;
  }

  public static void deleteSessionErrorMessage(String sessionId) {
    sessionErrorMessages.remove(sessionId);
  }

  @Override
  public void authenticate(JsonObject credentials, Handler<AsyncResult<User>> resultHandler) {
    sessionId = credentials.getString(Field.TOKEN.getName());
    Item userSession = Sessions.getSessionById(sessionId);
    if (userSession != null) {
      String userId = Sessions.getUserIdFromPK(userSession.getString(Field.PK.getName()));
      Item user = Users.getUserById(userId);
      if (user != null) {
        // check if user is enabled
        if (!user.hasAttribute(Field.ENABLED.getName())
            || !user.getBoolean(Field.ENABLED.getName())) {
          putSessionErrorMessage(AuthErrorCodes.USER_DISABLED.name());
        } else {
          User userInstance = User.create(
              new JsonObject().put(Field.SESSION_ID.getName(), sessionId),
              new JsonObject()
                  .put("session", userSession.toJSON())
                  .put(Field.USER.getName(), user.toJSON()));
          // giving admin authorization to user instance
          if (user.getList(Field.ROLES.getName()).contains("1")) {
            getAdminAuthorizationProvider().getAuthorizations(userInstance);
          }

          resultHandler.handle(Future.succeededFuture(userInstance));
          return;
        }
      } else {
        putSessionErrorMessage(AuthErrorCodes.USER_NOT_FOUND.name());
      }
    } else {
      putSessionErrorMessage(AuthErrorCodes.SESSION_NOT_FOUND.name());
    }
    // remove the key from the RateLimiterMap if there is no valid user session
    RateLimitationHandler.removeRateLimiterForKey(rateLimiterKey);
    resultHandler.handle(Future.failedFuture(new HttpException()));
  }

  public boolean authenticate(RoutingContext routingContext, String sessionId) {
    return authenticate(routingContext, sessionId, true);
  }

  public boolean authenticate(RoutingContext routingContext, String sessionId, boolean shouldFail) {
    AtomicBoolean isAuth = new AtomicBoolean(false);
    rateLimiterKey = RateLimitationHandler.getKey(routingContext);
    authenticate(new JsonObject().put(Field.TOKEN.getName(), sessionId), auth -> {
      if (auth.succeeded() && auth.result() != null) {
        routingContext.setUser(auth.result());
        isAuth.set(true);
      } else if (shouldFail) {
        routingContext.fail(new HttpException(401, auth.cause()));
      }
    });
    return isAuth.get();
  }

  private void putSessionErrorMessage(String message) {
    if (sessionErrorMessages == null) {
      sessionErrorMessages = new JsonObject();
    }

    sessionErrorMessages.put(sessionId, message);
  }

  public enum AuthErrorCodes {
    USER_NOT_FOUND,
    SESSION_NOT_FOUND,
    USER_DISABLED
  }
}
