package geoRural.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.entity.ImovelRural;
import geoRural.service.GeoJsonService;
import geoRural.service.ImovelRuralService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ImovelRuralController.class)
class ImovelRuralControllerTest {

    /** O contrato da US03/US04. Nem a mais, nem a menos. */
    private static final List<String> CAMPOS_PUBLICOS =
            List.of("codImovel", "municipio", "estado", "areaHa", "geometria");

    @Autowired
    private MockMvc mockMvc;

    /** Jackson 2 local: o slice do Boot 4 expoe Jackson 3, e o projeto usa o 2. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockitoBean
    private ImovelRuralService service;

    @MockitoBean
    private GeoJsonService geoJsonService;

    @Test
    void naoVazaCampoInternoDaEntidade() throws Exception {
        when(service.buscarPorCodImovel("TESTE-001")).thenReturn(imovelDeTeste());
        when(geoJsonService.converter(any())).thenReturn(Map.of("type", "Polygon"));

        String corpo = mockMvc.perform(get("/api/v1/imoveis/TESTE-001"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> resposta =
                MAPPER.readValue(corpo, new TypeReference<>() {});

        assertThat(resposta.keySet())
                .containsExactlyInAnyOrderElementsOf(CAMPOS_PUBLICOS);
    }

    @Test
    void filtroPorEstadoChegaAoServico() throws Exception {
        when(service.listarPorEstado("PR")).thenReturn(List.of(imovelDeTeste()));

        mockMvc.perform(get("/api/v1/imoveis").param("estado", "PR"))
                .andExpect(status().isOk());

        verify(service).listarPorEstado("PR");
        verify(service, never()).listarTodos();
    }

    @Test
    void filtroPorCodIbgeTemPrioridadeSobreOEstado() throws Exception {
        when(service.listarPorCodIbge("4113700")).thenReturn(List.of(imovelDeTeste()));

        mockMvc.perform(get("/api/v1/imoveis").param("codIbge", "4113700").param("estado", "PR"))
                .andExpect(status().isOk());

        verify(service).listarPorCodIbge("4113700");
        verify(service, never()).listarPorEstado(any());
    }

    @Test
    void semFiltroListaTodos() throws Exception {
        when(service.listarTodos()).thenReturn(List.of(imovelDeTeste()));

        mockMvc.perform(get("/api/v1/imoveis"))
                .andExpect(status().isOk());

        verify(service).listarTodos();
    }

    private static ImovelRural imovelDeTeste() {
        ImovelRural imovel = new ImovelRural();
        imovel.setId(42L);                 // interno — não pode aparecer
        imovel.setDatasetVersaoId(7L);     // interno — não pode aparecer
        imovel.setCodImovel("TESTE-001");
        imovel.setMunicipio("Londrina");
        imovel.setEstado("PR");
        imovel.setAreaHa(new BigDecimal("100.5000"));
        return imovel;
    }
}
