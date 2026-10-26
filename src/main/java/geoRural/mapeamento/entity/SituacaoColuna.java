package geoRural.mapeamento.entity;

/**
 * Situação de uma coluna detectada, derivada (nunca gravada):
 *
 *   AUSENTE  = a coluna não veio no último arquivo recebido (vale mesmo se já tiver regra,
 *              porque é o alerta que importa: a regra aponta para uma coluna que sumiu)
 *   MAPEADA  = veio no último arquivo e existe regra em regra_campo
 *   NOVA     = veio no último arquivo e ainda não tem regra
 */
public enum SituacaoColuna {

    MAPEADA("mapeada"),
    NOVA("nova"),
    AUSENTE("ausente");

    private final String codigo;

    SituacaoColuna(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }

    public static SituacaoColuna de(boolean presenteNoUltimoArquivo, boolean mapeada) {
        if (!presenteNoUltimoArquivo) {
            return AUSENTE;
        }
        return mapeada ? MAPEADA : NOVA;
    }
}