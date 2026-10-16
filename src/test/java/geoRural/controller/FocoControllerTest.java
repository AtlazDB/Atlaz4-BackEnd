package geoRural.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.FocosNoMapaResponse;
import geoRural.dto.MapaImoveisResponse;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.FocoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FocoController.class)
class FocoControllerTest {

    /** Jackson 2 local: o slice do Boot 4 expoe Jackson 3, e o projeto usa o 2. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FocoService service;

    @Test
    @SuppressWarnings("unchecked")
    void devolvePontosComoGeoJsonEARegraDaLegenda() throws Exception {
        var feature = MapaImoveisResponse.Feature.de(Map.of("idInpe", "a"),
                "{\"type\":\"Point\",\"coordinates\":[-50.1,-25.1]}");
        when(service.listarNoMapa(-51.0, -26.0, -50.0, -25.0, 2000)).thenReturn(FocosNoMapaResponse.de(
                List.of(feature), false, 2000, "AQUA_M-T", 12, LocalDateTime.now().minusMonths(12), LocalDateTime.now()));

        String corpo = mockMvc.perform(get("/api/v1/focos?minLon=-51&minLat=-26&maxLon=-50&maxLat=-25"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});
        assertThat(resposta).containsEntry("type", "FeatureCollection").containsEntry("satelite", "AQUA_M-T");
        Map<String, Object> primeira = ((List<Map<String, Object>>) resposta.get("features")).get(0);
        assertThat((Map<String, Object>) primeira.get("geometry")).containsEntry("type", "Point");
    }

    @Test
    void semAreaResponde400() throws Exception {
        when(service.listarNoMapa(any(), any(), any(), any(), anyInt()))
                .thenThrow(new RequisicaoInvalidaException("Informe a área do mapa.", List.of("minLon")));

        mockMvc.perform(get("/api/v1/focos")).andExpect(status().isBadRequest());
    }
}
