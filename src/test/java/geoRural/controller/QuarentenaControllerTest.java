package geoRural.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.RevalidationError;
import geoRural.dto.RevalidationResponse;
import geoRural.entity.RegistroQuarentena;
import geoRural.exception.QuarantineClosedException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.exception.ResourceNotFoundException;
import geoRural.service.QuarentenaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuarentenaController.class)
class QuarentenaControllerTest {

    /** Jackson 2 local: o slice do Boot 4 expoe Jackson 3, e o projeto usa o 2. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuarentenaService service;

    @Test
    @SuppressWarnings("unchecked")
    void revalidarDevolveStatusErrosERegistro() throws Exception {
        RegistroQuarentena registro = new RegistroQuarentena();
        registro.setId(1L);
        when(service.revalidar(1L)).thenReturn(new RevalidationResponse("REPROVADO",
                List.of(new RevalidationError("CAMPO_OBRIGATORIO_AUSENTE", "cod_imovel", "Faltou.", null)),
                registro));

        String corpo = mockMvc.perform(post("/api/v1/quarentena/1/revalidar"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});
        assertThat(resposta).containsEntry("status", "REPROVADO").containsKeys("erros", "quarantine");
        Map<String, Object> erro = ((List<Map<String, Object>>) resposta.get("erros")).get(0);
        assertThat(erro).containsEntry("tipo_erro", "CAMPO_OBRIGATORIO_AUSENTE").containsEntry("campo", "cod_imovel");
    }

    @Test
    void registroInexistenteResponde404ComMensagem() throws Exception {
        when(service.buscarPorId(99L)).thenThrow(new ResourceNotFoundException("Registro de quarentena não encontrado: 99"));

        String corpo = mockMvc.perform(get("/api/v1/quarentena/99"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readValue(corpo, new TypeReference<Map<String, Object>>() {})).containsKey("mensagem");
    }

    @Test
    void registroEncerradoResponde409() throws Exception {
        when(service.revalidar(1L)).thenThrow(new QuarantineClosedException(1L, "DESCARTADO"));

        mockMvc.perform(post("/api/v1/quarentena/1/revalidar"))
                .andExpect(status().isConflict());
    }

    @Test
    void payloadCorrigidoInvalidoResponde400() throws Exception {
        when(service.corrigir(eq(1L), any()))
                .thenThrow(new RequisicaoInvalidaException("O payload corrigido precisa ser um objeto JSON.",
                        List.of("payloadCorrigido")));

        mockMvc.perform(post("/api/v1/quarentena/1/corrigir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payloadCorrigido\": \"não é json\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void descarteSemJustificativaResponde400SemChamarOService() throws Exception {
        mockMvc.perform(post("/api/v1/quarentena/1/descartar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\": \"  \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void geometriaDevolveGeoJson() throws Exception {
        when(service.buscarGeometria(1L)).thenReturn(Map.of("type", "Polygon"));

        String corpo = mockMvc.perform(get("/api/v1/quarentena/1/geometria"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readValue(corpo, new TypeReference<Map<String, Object>>() {}))
                .containsEntry("type", "Polygon");
    }

    /** GEOMETRIA_MALFORMADA: não há o que desenhar. */
    @Test
    void semGeometriaResponde204() throws Exception {
        when(service.buscarGeometria(1L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/quarentena/1/geometria"))
                .andExpect(status().isNoContent());
    }
}
