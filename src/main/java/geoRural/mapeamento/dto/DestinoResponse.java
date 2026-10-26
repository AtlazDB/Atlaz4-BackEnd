package geoRural.mapeamento.dto;

import java.util.List;

/**
 * Uma tabela da Zona Tratada que pode receber dados pelo mapeamento, com os campos que o
 * mapeamento pode preencher.
 *
 * {@code tipo} já vem no vocabulário da regra_campo (TEXTO, INTEIRO, DECIMAL, DATA, GEOMETRIA),
 * para a tela sugerir o tipo da regra. {@code obrigatorio}: a tabela não aceita o campo vazio.
 * {@code observacao}: quando o campo tem algo a saber (preenchido sozinho se não for mapeado).
 */
public record DestinoResponse(String tabela, String rotulo, List<Campo> campos) {

    public record Campo(String nome, String tipo, boolean obrigatorio, String observacao) {
    }
}
