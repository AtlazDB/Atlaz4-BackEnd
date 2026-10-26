package geoRural.mapeamento.entity;

/**
 * Lista fixa de tabelas que a integridade cruzada pode referenciar.
 * O código é exatamente o valor aceito pelo CHECK de regra_campo.referencia_cruzada (V9).
 * Se a lista crescer, é preciso uma migration que altere esse CHECK.
 */
public enum ReferenciaCruzada {

    MUNICIPIO("municipio", "Município"),
    IMOVEL_RURAL("imovel_rural", "Imóvel rural");

    private final String codigo;
    private final String nome;

    ReferenciaCruzada(String codigo, String nome) {
        this.codigo = codigo;
        this.nome = nome;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNome() {
        return nome;
    }

    public static boolean existe(String codigo) {
        for (ReferenciaCruzada r : values()) {
            if (r.codigo.equals(codigo)) {
                return true;
            }
        }
        return false;
    }
}