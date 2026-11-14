/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SettingsResourceTest {
    private static final String PATH = "/api/settings/retention";
    static final String DEFAULTS = "{\"maxAgeDays\":30,\"maxBuilds\":50}";

    @Test void anonymousCannotRead() { given().get(PATH).then().statusCode(401); }

    @Test @TestSecurity(user = "lead", roles = "lead")
    void leadCannotReadOrUpdate() {
        given().get(PATH).then().statusCode(403);
        given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403);
    }

    @Test @TestSecurity(user = "member", roles = "member")
    void memberCannotUpdate() { given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403); }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void adminSavesRetention() {
        try {
            given().contentType("application/json").body("{\"maxAgeDays\":7,\"maxBuilds\":0}").put(PATH).then().statusCode(200)
                    .body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
            given().get(PATH).then().statusCode(200).body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
        } finally {
            given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(200);
        }
        given().get(PATH).then().statusCode(200).body("maxAgeDays", equalTo(30), "maxBuilds", equalTo(50));
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void rejectsInvalidLimits() {
        for (String body : new String[] {"{\"maxAgeDays\":-1,\"maxBuilds\":50}", "{\"maxAgeDays\":30,\"maxBuilds\":-1}",
                "{\"maxAgeDays\":3651,\"maxBuilds\":50}", "{\"maxAgeDays\":30,\"maxBuilds\":10001}", "{\"maxAgeDays\":30}", "{}"}) {
            given().contentType("application/json").body(body).put(PATH).then().statusCode(400);
        }
    }

    private static final String QUEUE = "/api/settings/queue";

    static void clearQueue() {
        QuarkusTransaction.requiringNew().run(() -> Settings.update("maxConcurrentBuilds = null, maxQueuedBuilds = null where id = 1"));
    }

    @Test void anonymousCannotReadQueue() { given().get(QUEUE).then().statusCode(401); }

    @Test @TestSecurity(user = "lead", roles = "lead")
    void leadCannotReadOrUpdateQueue() {
        given().get(QUEUE).then().statusCode(403);
        given().contentType("application/json").body("{\"maxConcurrent\":2,\"maxQueued\":10}").put(QUEUE).then().statusCode(403);
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void adminSavesQueueOverConfiguredDefaults() {
        given().get(QUEUE).then().statusCode(200).body("maxConcurrent", equalTo(1), "maxQueued", equalTo(100));
        try {
            given().contentType("application/json").body("{\"maxConcurrent\":4,\"maxQueued\":10}").put(QUEUE).then().statusCode(200)
                    .body("maxConcurrent", equalTo(4), "maxQueued", equalTo(10));
            given().get(QUEUE).then().statusCode(200).body("maxConcurrent", equalTo(4), "maxQueued", equalTo(10));
        } finally {
            clearQueue();
        }
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void rejectsInvalidQueue() {
        for (String body : new String[] {"{\"maxConcurrent\":0,\"maxQueued\":10}", "{\"maxConcurrent\":17,\"maxQueued\":10}",
                "{\"maxConcurrent\":1,\"maxQueued\":0}", "{\"maxConcurrent\":1,\"maxQueued\":1001}", "{\"maxConcurrent\":1}", "{}"}) {
            given().contentType("application/json").body(body).put(QUEUE).then().statusCode(400);
        }
    }

    private static final String CHECKOUT = "/api/settings/checkout";

    static void clearCheckout() {
        QuarkusTransaction.requiringNew().run(() -> Settings.update("maxCheckoutMib = null, maxCheckoutFiles = null where id = 1"));
    }

    @Test void anonymousCannotReadCheckout() { given().get(CHECKOUT).then().statusCode(401); }

    @Test @TestSecurity(user = "lead", roles = "lead")
    void leadCannotReadOrUpdateCheckout() {
        given().get(CHECKOUT).then().statusCode(403);
        given().contentType("application/json").body("{\"maxSizeMib\":10,\"maxFiles\":10}").put(CHECKOUT).then().statusCode(403);
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void adminSavesCheckoutOverConfiguredDefaults() {
        given().get(CHECKOUT).then().statusCode(200).body("maxSizeMib", equalTo(1024), "maxFiles", equalTo(100000));
        try {
            given().contentType("application/json").body("{\"maxSizeMib\":64,\"maxFiles\":500}").put(CHECKOUT).then().statusCode(200)
                    .body("maxSizeMib", equalTo(64), "maxFiles", equalTo(500));
            given().get(CHECKOUT).then().statusCode(200).body("maxSizeMib", equalTo(64), "maxFiles", equalTo(500));
        } finally {
            clearCheckout();
        }
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void rejectsInvalidCheckout() {
        for (String body : new String[] {"{\"maxSizeMib\":0,\"maxFiles\":10}", "{\"maxSizeMib\":102401,\"maxFiles\":10}",
                "{\"maxSizeMib\":1,\"maxFiles\":0}", "{\"maxSizeMib\":1,\"maxFiles\":1000001}", "{\"maxSizeMib\":1}", "{}"}) {
            given().contentType("application/json").body(body).put(CHECKOUT).then().statusCode(400);
        }
    }
}
