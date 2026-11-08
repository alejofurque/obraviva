package com.obraviva.platform.documents;

import com.obraviva.platform.documents.DocumentDtos.RequestDocumentUploadRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** El alta de un documento tal como la manda el cliente. */
class DocumentRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("un documento de la obra llega sin unidad y con la categoría como texto")
    void documentForWholeDevelopment() {
        RequestDocumentUploadRequest request = JSON.readValue("""
                {"title":"Reglamento","category":"REGLAMENTO","filename":"reglamento.pdf","contentType":"application/pdf"}""",
                RequestDocumentUploadRequest.class);

        assertThat(request.unitId()).isNull();
        assertThat(request.category()).isEqualTo(UnitDocument.Category.REGLAMENTO);
    }
}
