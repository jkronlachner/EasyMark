package easymark.webserver;

import easymark.*;
import easymark.database.*;
import easymark.database.models.*;
import easymark.webserver.constants.*;
import io.javalin.*;
import io.javalin.http.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import static io.javalin.core.security.SecurityUtil.*;

public class WebServer {
    public static final int PORT = 8080;

    public static Javalin create() {
        Javalin app = Javalin.create();

        app.config.accessManager((handler, ctx, permittedRoles) -> {
            if (isLoggedIn(ctx) && checkForExpiredSession(ctx))
                return;

            if (!permittedRoles.isEmpty()) {
                Set<UserRole> roles = ctx.sessionAttribute(SessionKeys.ROLES);
                if (roles == null || permittedRoles.stream().noneMatch(roles::contains))
                    throw new ForbiddenResponse("Not allowed");
            }

            handler.handle(ctx);
        });

        app.post("/login", ctx -> {
            final ForbiddenResponse FORBIDDEN = new ForbiddenResponse("Forbidden");

            if (!checkCSRFToken(ctx, ctx.formParam(FormKeys.CSRF_TOKEN)))
                throw FORBIDDEN;

            String providedAccessTokenStr = ctx.formParam("accessToken");
            if (providedAccessTokenStr == null || providedAccessTokenStr.length() != Cryptography.ACCESS_TOKEN_LENGTH)
                throw FORBIDDEN;
            String providedIdentifier = providedAccessTokenStr.substring(0, Cryptography.ACCESS_TOKEN_IDENTIFIER_LENGTH);
            String providedSecret = providedAccessTokenStr.substring(Cryptography.ACCESS_TOKEN_IDENTIFIER_LENGTH);

            try (DatabaseHandle dbHandle = DBMS.openRead()) {
                Admin matchingAdmin = checkAccessTokenMatch(
                        ctx, dbHandle.get().getAdmins(),
                        providedIdentifier, providedSecret,
                        Admin::getAccessToken,
                        UserRole.ADMIN);
                Entity matchingEntity = matchingAdmin;

                if (matchingEntity == null) {
                    matchingEntity = checkAccessTokenMatch(
                            ctx, dbHandle.get().getParticipants(),
                            providedIdentifier, providedSecret,
                            Participant::getCat,
                            UserRole.PARTICIPANT);
                } else {
                    String iek = matchingAdmin.getIek();
                    String iekSalt = matchingAdmin.getIekSalt();
                    String uek = Cryptography.decryptUEK(iek, iekSalt, providedAccessTokenStr);
                    String set = Cryptography.generateSET();
                    String sekSalt = Cryptography.generateEncryptionSalt();
                    String sek = Cryptography.encryptUEK(uek, sekSalt, set);
                    ctx.cookie(CookieKeys.SET, set);
                    ctx.sessionAttribute(SessionKeys.SEK, sek);
                    ctx.sessionAttribute(SessionKeys.SEK_SALT, sekSalt);
                }
                if (matchingEntity == null)
                    throw FORBIDDEN;
                ctx.sessionAttribute(SessionKeys.ENTITY_ID, matchingEntity.getId());
            }
            ctx.req.changeSessionId();
            ctx.sessionAttribute(SessionKeys.LAST_SESSION_ACTION, LocalDateTime.now());
            ctx.redirect("/");
        });

        app.post("/logout", ctx -> {
            if (!checkCSRFToken(ctx, ctx.formParam(FormKeys.CSRF_TOKEN)))
                throw new ForbiddenResponse("Forbidden");
            logOut(ctx);
            ctx.redirect("/");
        });

        app.get("/", ctx -> {
            Map<String, Object> model = new HashMap<>();
            model.put(ModelKeys.LOG_IN_OUT_CSRF_TOKEN, makeCSRFToken(ctx));

            Set<UserRole> roles = ctx.sessionAttribute(SessionKeys.ROLES);
            if (roles != null) {
                UserRole primaryRole = roles.contains(UserRole.ADMIN)
                        ? UserRole.ADMIN
                        : UserRole.PARTICIPANT;
                model.put(ModelKeys.ROLE, primaryRole.name());
            }

            ctx.render("index.peb", model);
        });

        app.get("/courses", ctx -> {
            try (DatabaseHandle db = DBMS.openRead()) {
                Map<String, Object> model = new HashMap<>();
                model.put(ModelKeys.COURSES, db.get().getCourses());
                model.put(ModelKeys.LOG_IN_OUT_CSRF_TOKEN, makeCSRFToken(ctx));

                String set = ctx.cookie(CookieKeys.SET);
                String sek = ctx.sessionAttribute(SessionKeys.SEK);
                String sekSalt = ctx.sessionAttribute(SessionKeys.SEK_SALT);
                if (set == null || sekSalt == null || sek == null) {
                    logOut(ctx);
                    throw new InternalServerErrorResponse();
                }
                String uek = Cryptography.decryptUEK(sek, sekSalt, set);

                Participant part = db.get()
                        .getParticipants()
                        .get(0);
                String encData = part.getName();
                String encSalt = part.getNameSalt();
                String data = Cryptography.decryptData(encData, encSalt, uek);
                model.put("encData", encData);
                model.put("data", data);

                ctx.render("courses.peb", model);
            }
        }, roles(UserRole.ADMIN));

        return app;
    }

    private static <E extends Entity> E checkAccessTokenMatch(
            Context ctx,
            List<E> table,
            String providedIdentifier,
            String providedSecret,
            Function<E, AccessToken> getAccessToken,
            UserRole roleIfMatch
    ) {
        Optional<E> matchingParticipant = table
                .stream()
                .filter(entity -> getAccessToken
                        .apply(entity)
                        .getIdentifier()
                        .equals(providedIdentifier))
                .findAny();
        if (matchingParticipant.isPresent()) {
            String participantSecret = getAccessToken
                    .apply(matchingParticipant.get())
                    .getSecret();
            if (Utils.PASSWORD_ENCODER.matches(providedSecret, participantSecret)) {
                ctx.sessionAttribute(SessionKeys.ROLES, Set.of(roleIfMatch));
                return matchingParticipant.get();
            }
        }
        return null;
    }

    private static CSRFToken makeCSRFToken(Context ctx) {
        CSRFToken newToken = CSRFToken.random();

        List<CSRFToken> sessionCsrfTokens = ctx.sessionAttribute(SessionKeys.CSRF_TOKENS);
        if (sessionCsrfTokens == null) {
            sessionCsrfTokens = new ArrayList<>();
        } else {
            filterInvalidCSRFTokens(ctx);
        }
        sessionCsrfTokens.add(newToken);
        ctx.sessionAttribute(SessionKeys.CSRF_TOKENS, sessionCsrfTokens);
        ctx.header("Cache-Control", "no-store");  // Needed so that back button doesn't break

        return newToken;
    }

    private static boolean checkCSRFToken(Context ctx, String providedToken) {
        if (providedToken == null)
            return false;

        List<CSRFToken> sessionCsrfTokens = ctx.sessionAttribute(SessionKeys.CSRF_TOKENS);
        if (sessionCsrfTokens == null)
            return false;
        filterInvalidCSRFTokens(ctx);

        Optional<CSRFToken> match = sessionCsrfTokens
                .stream()
                .filter(csrfToken -> csrfToken.getValue().equals(providedToken))
                .findAny();

        match.ifPresent(sessionCsrfTokens::remove);
        ctx.sessionAttribute(SessionKeys.CSRF_TOKENS, sessionCsrfTokens);
        return match.isPresent();
    }

    private static void filterInvalidCSRFTokens(Context ctx) {
        List<CSRFToken> sessionCsrfTokens = ctx.sessionAttribute(SessionKeys.CSRF_TOKENS);
        if (sessionCsrfTokens != null) {
            sessionCsrfTokens = sessionCsrfTokens
                    .stream()
                    .filter(CSRFToken::isValid)
                    .collect(Collectors.toList());
            ctx.sessionAttribute(SessionKeys.CSRF_TOKENS, sessionCsrfTokens);
        }
    }

    private static boolean checkForExpiredSession(Context ctx) {
        LocalDateTime lastSessionAction = ctx.sessionAttribute(SessionKeys.LAST_SESSION_ACTION);
        if (lastSessionAction == null || LocalDateTime.now().minusHours(5).isAfter(lastSessionAction)) {
            logOut(ctx);
            ctx.redirect("/?message=sessionExpired");
            return true;
        } else {
            ctx.sessionAttribute(SessionKeys.LAST_SESSION_ACTION, LocalDateTime.now());
            return false;
        }
    }

    private static boolean isLoggedIn(Context ctx) {
        Set<UserRole> roles = ctx.sessionAttribute(SessionKeys.ROLES);
        return roles != null && (roles.contains(UserRole.ADMIN) || roles.contains(UserRole.PARTICIPANT));
    }

    private static void logOut(Context ctx) {
        ctx.sessionAttribute(SessionKeys.CSRF_TOKENS, null);
        ctx.sessionAttribute(SessionKeys.ROLES, null);
        ctx.sessionAttribute(SessionKeys.ENTITY_ID, null);
        ctx.sessionAttribute(SessionKeys.SEK, null);
        ctx.sessionAttribute(SessionKeys.SEK_SALT, null);
        ctx.removeCookie(CookieKeys.SET);
        ctx.req.changeSessionId();
    }
}
