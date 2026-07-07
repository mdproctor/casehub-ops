package io.casehub.ops.app.rest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DeploymentResourceTest {

    @Test
    void deployApplication() {
        // Create an application first
        String appId = given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-deploy")
                .body("""
                    {"name": "deploy-app", "description": "test", "servicesJson": "[]"}
                    """)
                .when().post("/api/applications")
                .then().statusCode(201)
                .extract().path("id");

        // Create a cluster
        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-deploy")
                .body("""
                    {"name": "deploy-cluster", "apiUrl": "https://localhost:6443",
                     "namespace": "default", "clusterType": "KUBERNETES"}
                    """)
                .when().post("/api/clusters")
                .then().statusCode(201);

        // Deploy the application
        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "test-tenant-deploy")
                .body("{}")
                .when().post("/api/applications/" + appId + "/deployments")
                .then().statusCode(202);
    }
}
