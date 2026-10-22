package geoRural.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Um indicador calculado, com tudo o que explica o número:
 *   numerador      IRL: ha de RL somados · IFC: quantidade de focos
 *   areaImovelHa   denominador, como estava no momento do cálculo
 *   saldoHa        só no IRL: positivo excedente, negativo déficit
 * e as duas versões de dados (imóvel e insumo) mais a regra que fez a conta.
 */
public record IndicadorResponse(
        String indicador,
        BigDecimal valor,
        String unidade,
        String status,
        BigDecimal numerador,
        BigDecimal areaImovelHa,
        BigDecimal saldoHa,
        OffsetDateTime calculadoEm,
        RegraCalculoResponse regra,
        VersaoDadosResponse versaoImovel,
        VersaoDadosResponse versaoInsumo
) {
}
