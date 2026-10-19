package geoRural.dto;

import java.util.Map;

/** A regra que produziu um indicador: código de negócio, versão e os parâmetros usados. */
public record RegraCalculoResponse(
        String codigo,
        int versao,
        String nome,
        String formula,
        String descricao,
        Map<String, Object> parametros
) {
}
