package geoRural.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.entity.Municipio;
import geoRural.exception.MunicipioNaoEncontradoException;
import geoRural.service.GeoJsonService;
import geoRural.service.MunicipioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MunicipioController.class)
class MunicipioControllerTest {

    /** Listagem: sem geometria, para a resposta não ficar pesada. */
    private static final List<String> CAMPOS_DA_LISTAGEM =
            List.of("codIbge", "nome", "estado", "regiao", "areaKm2");

    /** Detalhe: os mesmos campos mais a geometria em GeoJSON. */
    private static final List<String> CAMPOS_DO_DETALHE =
            List.of("codIbge", "nome", "estado", "regiao", "areaKm2", "geometria");

    @Autowired
    private MockMvc mockMvc;

    /** Jackson 2 local: o slice do Boot 4 expoe Jackson 3, e o projeto usa o 2. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockitoBean
    private MunicipioService service;

    @MockitoBean
    private GeoJsonService geoJsonService;

    @Test
    void listaPorEstadoSemGeometriaNemCampoInterno() throws Exception {
        when(service.listar("PR")).thenReturn(List.of(londrina()));

        String corpo = mockMvc.perform(get("/api/v1/municipios").param("estado", "PR"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});

        verify(service).listar("PR");
        assertThat(resposta).hasSize(1);
        assertThat(resposta.get(0).keySet()).containsExactlyInAnyOrderElementsOf(CAMPOS_DA_LISTAGEM);
        assertThat(resposta.get(0)).containsEntry("codIbge", "4113700");
    }

    @Test
    void buscaPorCodigoDevolveAGeometriaEmGeoJson() throws Exception {
        when(service.buscarPorCodIbge("4113700")).thenReturn(londrina());
        when(geoJsonService.converter(any())).thenReturn(Map.of("type", "Polygon"));

        String corpo = mockMvc.perform(get("/api/v1/municipios/4113700"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});

        assertThat(resposta.keySet()).containsExactlyInAnyOrderElementsOf(CAMPOS_DO_DETALHE);
        assertThat(resposta.get("geometria")).isEqualTo(Map.of("type", "Polygon"));
    }

    @Test
    void municipioInexistenteDevolve404ComMensagem() throws Exception {
        when(service.buscarPorCodIbge("0000000"))
                .thenThrow(new MunicipioNaoEncontradoException("0000000"));

        String corpo = mockMvc.perform(get("/api/v1/municipios/0000000"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});

        assertThat(resposta.keySet()).containsExactly("mensagem");
        assertThat((String) resposta.get("mensagem")).contains("0000000");
    }

    private static Municipio londrina() {
        Municipio municipio = new Municipio();
        municipio.setId(42L);               // interno — não pode aparecer
        municipio.setDatasetVersaoId(7L);   // interno — não pode aparecer
        municipio.setCodIbge("4113700");
        municipio.setNome("Londrina");
        municipio.setEstado("PR");
        municipio.setRegiao("Sul");
        municipio.setAreaKm2(new BigDecimal("1651.4000"));
        return municipio;
    }
}
