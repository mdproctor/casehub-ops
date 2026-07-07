package io.casehub.ops.app.rest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ClusterResourceTest {

    @Test
    void registerAndListCluster() {
        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant")
                .body("""
                    {"name": "test-cluster", "apiUrl": "https://localhost:6443",
                     "namespace": "default", "clusterType": "KUBERNETES"}
                    """)
                .when().post("/api/clusters")
                .then().statusCode(201)
                .body("name", equalTo("test-cluster"))
                .body("id", notNullValue());

        given()
                .header("X-Tenancy-ID", "test-tenant")
                .when().get("/api/clusters")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void getClusterById() {
        String id = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-get")
                .body("""
                    {"name": "get-cluster", "apiUrl": "https://localhost:6443",
                     "namespace": "default", "clusterType": "KUBERNETES"}
                    """)
                .when().post("/api/clusters")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .when().get("/api/clusters/" + id)
                .then().statusCode(200)
                .body("name", equalTo("get-cluster"));
    }

    @Test
    void returns404ForMissingCluster() {
        given()
                .when().get("/api/clusters/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404);
    }

    @Test
    void deleteCluster() {
        String id = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-delete")
                .body("""
                    {"name": "delete-cluster", "apiUrl": "https://localhost:6443",
                     "namespace": "default", "clusterType": "KUBERNETES"}
                    """)
                .when().post("/api/clusters")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .header("X-Tenancy-ID", "test-tenant-delete")
                .when().delete("/api/clusters/" + id)
                .then().statusCode(204);
    }

    @Test
    void testClusterConnectivity() {
        String id = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-connectivity")
                .body("""
                    {"name": "connectivity-cluster", "apiUrl": "https://localhost:6443",
                     "namespace": "default", "clusterType": "KUBERNETES"}
                    """)
                .when().post("/api/clusters")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .when().post("/api/clusters/" + id + "/test")
                .then().statusCode(200);
    }
}
