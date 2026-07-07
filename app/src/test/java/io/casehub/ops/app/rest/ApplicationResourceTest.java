package io.casehub.ops.app.rest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ApplicationResourceTest {

    @Test
    void createAndListApplication() {
        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant")
                .body("""
                    {"name": "test-app", "description": "test", "servicesJson": "[]"}
                    """)
                .when().post("/api/applications")
                .then().statusCode(201)
                .body("name", equalTo("test-app"))
                .body("id", notNullValue());

        given()
                .header("X-Tenancy-ID", "test-tenant")
                .when().get("/api/applications")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void returns404ForMissingApplication() {
        given()
                .when().get("/api/applications/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404);
    }

    @Test
    void deleteApplication() {
        String id = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-delete")
                .body("""
                    {"name": "delete-app", "description": "test", "servicesJson": "[]"}
                    """)
                .when().post("/api/applications")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .header("X-Tenancy-ID", "test-tenant-delete")
                .when().delete("/api/applications/" + id)
                .then().statusCode(202);
    }

    @Test
    void getApplicationById() {
        String id = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-get")
                .body("""
                    {"name": "get-app", "description": "test", "servicesJson": "[]"}
                    """)
                .when().post("/api/applications")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .when().get("/api/applications/" + id)
                .then().statusCode(200)
                .body("name", equalTo("get-app"));
    }
}
