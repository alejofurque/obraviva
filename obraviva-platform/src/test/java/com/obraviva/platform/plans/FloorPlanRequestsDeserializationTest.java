package com.obraviva.platform.plans;

import com.obraviva.platform.plans.FloorPlanDtos.CreateFloorPlanRequest;
import com.obraviva.platform.plans.FloorPlanDtos.ReplaceRoomsRequest;
import com.obraviva.platform.plans.FloorPlanDtos.UpdateFloorPlanRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FloorPlanRequestsDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("la planta nueva se lee con dimensiones enteras o decimales")
    void createBodyIsAccepted() {
        CreateFloorPlanRequest request = JSON.readValue(
                """
                {"name":"Planta baja","widthM":12,"depthM":9.5}""",
                CreateFloorPlanRequest.class);

        assertThat(request.widthM()).isEqualByComparingTo("12");
        assertThat(request.depthM()).isEqualByComparingTo("9.5");
    }

    @Test
    @DisplayName("la edición de planta lee su orden")
    void updateBodyIsAccepted() {
        UpdateFloorPlanRequest request = JSON.readValue(
                """
                {"name":"Planta alta","widthM":12,"depthM":9,"orderIndex":1}""",
                UpdateFloorPlanRequest.class);

        assertThat(request.orderIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("los ambientes se leen con contorno y superficie declarada opcional")
    void roomsBodyIsAccepted() {
        ReplaceRoomsRequest request = JSON.readValue(
                """
                {"rooms":[
                  {"name":"Living","polygon":[0,0,4,0,4,5,0,5]},
                  {"name":"Cocina","polygon":[4,0,7,0,7,3,4,3],"declaredAreaM2":9.1}
                ]}""",
                ReplaceRoomsRequest.class);

        assertThat(request.rooms()).hasSize(2);
        assertThat(request.rooms().get(0).polygon()).hasSize(8);
        assertThat(request.rooms().get(0).declaredAreaM2()).isNull();
        assertThat(request.rooms().get(1).declaredAreaM2()).isEqualByComparingTo("9.1");
    }
}
