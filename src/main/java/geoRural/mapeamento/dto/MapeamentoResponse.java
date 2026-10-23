package geoRural.mapeamento.dto;

import geoRural.mapeamento.entity.RegraCampo;

/** Uma regra de mapeamento (linha de regra_campo) com o nome da coluna de origem e o código do domínio. */
public record MapeamentoResponse(
        Long id,
        Long colunaId,
        String colunaNome,
        String destinoTabela,
        String campoDestino,
        String tipoDado,
        String formatoOrigem,
        boolean obrigatorio,
        boolean chave,
        boolean bloquearRegressao,
        boolean validarCpfCnpj,
        String politicaGeometria,
        boolean recortarPr,
        Long dominioId,
        String dominioCodigo,
        String referenciaCruzada
) {
    public static MapeamentoResponse de(RegraCampo r) {
        return new MapeamentoResponse(
                r.getId(),
                r.getFonteColuna().getId(),
                r.getFonteColuna().getNomeColuna(),
                r.getDestinoTabela(),
                r.getCampoDestino(),
                r.getTipoDado(),
                r.getFormatoOrigem(),
                r.isObrigatorio(),
                r.isChave(),
                r.isBloquearRegressao(),
                r.isValidarCpfCnpj(),
                r.getPoliticaGeometria(),
                r.isRecortarPr(),
                r.getDominio() == null ? null : r.getDominio().getId(),
                r.getDominio() == null ? null : r.getDominio().getCodigo(),
                r.getReferenciaCruzada()
        );
    }
}