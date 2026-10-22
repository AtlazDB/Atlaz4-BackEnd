package geoRural.mapeamento.entity;

import geoRural.entity.Fonte;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/**
 * Mapeamento de uma coluna de origem para um campo de destino, com as regras de qualidade
 * (tabela REGRA_CAMPO, V9). Na 5.4 só é lida; a 5.5 passa a criar e remover.
 *
 * No banco, a FK (FONTE_COLUNA_ID, FONTE_ID) garante que a coluna pertence à mesma fonte da regra.
 */
@Entity
@Table(name = "REGRA_CAMPO")
public class RegraCampo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "FONTE_ID", nullable = false)
    private Fonte fonte;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "FONTE_COLUNA_ID", nullable = false)
    private FonteColuna fonteColuna;

    @Column(name = "DESTINO_TABELA", nullable = false)
    private String destinoTabela;

    @Column(name = "CAMPO_DESTINO", nullable = false)
    private String campoDestino;

    @Column(name = "TIPO_DADO", nullable = false)
    private String tipoDado;

    @Column(name = "FORMATO_ORIGEM")
    private String formatoOrigem;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "OBRIGATORIO", nullable = false)
    private boolean obrigatorio;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "CHAVE", nullable = false)
    private boolean chave;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "BLOQUEAR_REGRESSAO", nullable = false)
    private boolean bloquearRegressao;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "VALIDAR_CPF_CNPJ", nullable = false)
    private boolean validarCpfCnpj;

    @Column(name = "POLITICA_GEOMETRIA")
    private String politicaGeometria;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "RECORTAR_PR", nullable = false)
    private boolean recortarPr;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DOMINIO_ID")
    private Dominio dominio;

    @Column(name = "REFERENCIA_CRUZADA")
    private String referenciaCruzada;

    @Column(name = "CRIADO_EM", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    public Long getId() { return id; }

    public Fonte getFonte() { return fonte; }
    public void setFonte(Fonte fonte) { this.fonte = fonte; }

    public FonteColuna getFonteColuna() { return fonteColuna; }
    public void setFonteColuna(FonteColuna fonteColuna) { this.fonteColuna = fonteColuna; }

    public String getDestinoTabela() { return destinoTabela; }
    public void setDestinoTabela(String destinoTabela) { this.destinoTabela = destinoTabela; }

    public String getCampoDestino() { return campoDestino; }
    public void setCampoDestino(String campoDestino) { this.campoDestino = campoDestino; }

    public String getTipoDado() { return tipoDado; }
    public void setTipoDado(String tipoDado) { this.tipoDado = tipoDado; }

    public String getFormatoOrigem() { return formatoOrigem; }
    public void setFormatoOrigem(String formatoOrigem) { this.formatoOrigem = formatoOrigem; }

    public boolean isObrigatorio() { return obrigatorio; }
    public void setObrigatorio(boolean obrigatorio) { this.obrigatorio = obrigatorio; }

    public boolean isChave() { return chave; }
    public void setChave(boolean chave) { this.chave = chave; }

    public boolean isBloquearRegressao() { return bloquearRegressao; }
    public void setBloquearRegressao(boolean bloquearRegressao) { this.bloquearRegressao = bloquearRegressao; }

    public boolean isValidarCpfCnpj() { return validarCpfCnpj; }
    public void setValidarCpfCnpj(boolean validarCpfCnpj) { this.validarCpfCnpj = validarCpfCnpj; }

    public String getPoliticaGeometria() { return politicaGeometria; }
    public void setPoliticaGeometria(String politicaGeometria) { this.politicaGeometria = politicaGeometria; }

    public boolean isRecortarPr() { return recortarPr; }
    public void setRecortarPr(boolean recortarPr) { this.recortarPr = recortarPr; }

    public Dominio getDominio() { return dominio; }
    public void setDominio(Dominio dominio) { this.dominio = dominio; }

    public String getReferenciaCruzada() { return referenciaCruzada; }
    public void setReferenciaCruzada(String referenciaCruzada) { this.referenciaCruzada = referenciaCruzada; }

    public OffsetDateTime getCriadoEm() { return criadoEm; }
}