package geoRural.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.IndicadorResponse;
import geoRural.dto.IndicadoresImovelResponse;
import geoRural.dto.RegraCalculoResponse;
import geoRural.dto.VersaoDadosResponse;
import geoRural.exception.ImovelNaoEncontradoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.IndicadorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndicadorController.class)
class IndicadorControllerTest {

    /** Jackson 2 local: o slice do Boot 4 expoe Jackson 3, e o projeto usa o 2. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IndicadorService service;

    @Test
    @SuppressWarnings("unchecked")
    void devolveValorRegraEAsDuasVersoes() throws Exception {
        when(service.buscarPorImovel("PR-1")).thenReturn(new IndicadoresImovelResponse("PR-1", List.of(irl())));

        String corpo = mockMvc.perform(get("/api/v1/imoveis/PR-1/indicadores"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> resposta = MAPPER.readValue(corpo, new TypeReference<>() {});
        Map<String, Object> indicador = ((List<Map<String, Object>>) resposta.get("indicadores")).get(0);
        assertThat(indicador.keySet()).containsExactlyInAnyOrder("indicador", "valor", "unidade", "status",
                "numerador", "areaImovelHa", "saldoHa", "calculadoEm", "regra", "versaoImovel", "versaoInsumo");
        assertThat((Map<String, Object>) indicador.get("regra")).containsEntry("codigo", "RN-IRL-Min20pct");
        assertThat((Map<String, Object>) indicador.get("versaoInsumo")).containsEntry("conjunto", "reserva_legal");
    }

    @Test
    void imovelInexistenteResponde404ComMensagem() throws Exception {
        when(service.buscarPorImovel("X")).thenThrow(new ImovelNaoEncontradoException("X"));

        String corpo = mockMvc.perform(get("/api/v1/imoveis/X/indicadores"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readValue(corpo, new TypeReference<Map<String, Object>>() {})).containsKey("mensagem");
    }

    @Test
    void indicadorInvalidoNoHistoricoResponde400() throws Exception {
        when(service.historico("PR-1", "ABC"))
                .thenThrow(new RequisicaoInvalidaException("Indicador inválido. Use: IRL, IFC.", List.of("indicador")));

        mockMvc.perform(get("/api/v1/imoveis/PR-1/indicadores/ABC/historico"))
                .andExpect(status().isBadRequest());
    }

    private static IndicadorResponse irl() {
        return new IndicadorResponse("IRL", new BigDecimal("15.4229"), "%", "CRITICO",
                new BigDecimal("15.5"), new BigDecimal("100.5"), new BigDecimal("-4.6"), OffsetDateTime.now(),
                new RegraCalculoResponse("RN-IRL-Min20pct", 1, "Reserva Legal", "f", "d", Map.of("percentual_minimo", 20)),
                new VersaoDadosResponse("imoveis_rurais", 4, 128L, OffsetDateTime.now(), "CAR", "CAR", 1L, "a.zip", "h"),
                new VersaoDadosResponse("reserva_legal", 2, 126L, OffsetDateTime.now(), "CAR-RL", "RL", 2L, "b.zip", "h"));
    }
}
