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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/**
 * Coluna detectada nos arquivos de uma fonte (tabela FONTE_COLUNA, V9).
 * Quem grava é a DAG detectar_colunas (Tarefa 1); o Java só lê.
 */
@Entity
@Table(name = "FONTE_COLUNA")
public class FonteColuna {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "FONTE_ID", nullable = false)
    private Fonte fonte;

    @Column(name = "NOME_COLUNA", nullable = false)
    private String nomeColuna;

    @Column(name = "TIPO_DETECTADO")
    private String tipoDetectado;

    /** JSON (array de valores de exemplo). Use ExemplosJson.paraLista para ler. */
    @Lob
    @Column(name = "EXEMPLOS")
    private String exemplos;

    @Column(name = "ORDEM")
    private Integer ordem;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "PRESENTE_ULTIMO_ARQUIVO", nullable = false)
    private boolean presenteUltimoArquivo = true;

    @Column(name = "PRIMEIRA_DETECCAO", insertable = false, updatable = false)
    private OffsetDateTime primeiraDeteccao;

    @Column(name = "ULTIMA_DETECCAO", insertable = false, updatable = false)
    private OffsetDateTime ultimaDeteccao;

    public Long getId() { return id; }
    public Fonte getFonte() { return fonte; }
    public String getNomeColuna() { return nomeColuna; }
    public String getTipoDetectado() { return tipoDetectado; }
    public String getExemplos() { return exemplos; }
    public Integer getOrdem() { return ordem; }
    public boolean isPresenteUltimoArquivo() { return presenteUltimoArquivo; }
    public OffsetDateTime getPrimeiraDeteccao() { return primeiraDeteccao; }
    public OffsetDateTime getUltimaDeteccao() { return ultimaDeteccao; }
}