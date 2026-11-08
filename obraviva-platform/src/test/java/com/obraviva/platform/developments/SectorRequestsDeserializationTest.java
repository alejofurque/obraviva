package com.obraviva.platform.developments;

import com.obraviva.platform.developments.CaptureSectorService.RenameSectorRequest;
import com.obraviva.platform.developments.CaptureSectorService.ReplaceSectorsRequest;
import com.obraviva.platform.media.MediaController.UpdateMediaSectorRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización de lo que manda el editor de sectores y el chip de sector de una foto,
 * desde el JSON real del cliente.
 */
class SectorRequestsDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("la lista del editor: cada fila con su nombre y el que tenía; una fila nueva sin previousName")
    void editorList() {
        ReplaceSectorsRequest request = JSON.readValue("""
                {"sectors":[{"name":"Living","previousName":"Living y cocina"},{"name":"Quincho"}]}""",
                ReplaceSectorsRequest.class);

        assertThat(request.names()).isNull();
        assertThat(request.sectors()).hasSize(2);
        assertThat(request.sectors().get(0).previousName()).isEqualTo("Living y cocina");
        assertThat(request.sectors().get(1).previousName()).isNull();
    }

    @Test
    @DisplayName("la forma anterior, con nombres, sigue valiendo")
    void legacyNames() {
        ReplaceSectorsRequest request = JSON.readValue("""
                {"names":["Frente","Baño"]}""", ReplaceSectorsRequest.class);

        assertThat(request.names()).containsExactly("Frente", "Baño");
        assertThat(request.sectors()).isNull();
    }

    @Test
    @DisplayName("renombrar y cambiar el sector de una foto, con nulo para General")
    void renameAndPhotoSector() {
        RenameSectorRequest rename = JSON.readValue("""
                {"from":"Living y cocina","to":"Living"}""", RenameSectorRequest.class);
        assertThat(rename.to()).isEqualTo("Living");

        assertThat(JSON.readValue("""
                {"sector":"Frente"}""", UpdateMediaSectorRequest.class).sector()).isEqualTo("Frente");
        assertThat(JSON.readValue("""
                {"sector":null}""", UpdateMediaSectorRequest.class).sector()).isNull();
    }
}
