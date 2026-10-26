package geoRural.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "FONTE")
public class Fonte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "NOME", nullable = false)
    private String nome;

    @Column(name = "SIGLA", nullable = false, unique = true)
    private String sigla;

    @Column(name = "ORGAO", nullable = false)
    private String orgao;

    @Column(name = "FORMATO", nullable = false)
    private String formato;

    @Column(name = "PERIODICIDADE", nullable = false)
    private String periodicidade;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    @CreationTimestamp
    @Column(name = "CRIADA_EM", nullable = false, updatable = false)
    private OffsetDateTime criadaEm;

    // --- NOVOS CAMPOS (Task T1 - Ingestão por Link e Execução) ---

    @Column(name = "MODALIDADE", nullable = false)
    private String modalidade = "MANUAL"; // MANUAL ou LINK

    @Column(name = "URL_ORIGEM")
    private String urlOrigem;

    @Column(name = "METODO_HTTP")
    private String metodoHttp = "GET";

    @Lob
    @Column(name = "CABECALHOS")
    private String cabecalhos;

    @Column(name = "CREDENCIAL_REF")
    private String credencialRef;

    @Lob
    @Column(name = "CORPO_REQUISICAO")
    private String corpoRequisicao;

    @Column(name = "PAGINACAO")
    private String paginacao;

    @Column(name = "CRON")
    private String cron;

    // --- Coleta pelo Airflow (V18) ---

    /** Pedido de coleta ainda não atendido pela DAG coleta_fontes. */
    @Column(name = "COLETA_SOLICITADA_EM")
    private OffsetDateTime coletaSolicitadaEm;

    @Column(name = "ULTIMA_COLETA_EM")
    private OffsetDateTime ultimaColetaEm;

    @Column(name = "ULTIMA_COLETA_MENSAGEM")
    private String ultimaColetaMensagem;

    // Construtores, Getters e Setters
    public Fonte() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getSigla() { return sigla; }
    public void setSigla(String sigla) { this.sigla = sigla; }

    public String getOrgao() { return orgao; }
    public void setOrgao(String orgao) { this.orgao = orgao; }

    public String getFormato() { return formato; }
    public void setFormato(String formato) { this.formato = formato; }

    public String getPeriodicidade() { return periodicidade; }
    public void setPeriodicidade(String periodicidade) { this.periodicidade = periodicidade; }

    public boolean isAtiva() { return ativa; }
    public void setAtiva(boolean ativa) { this.ativa = ativa; }

    public OffsetDateTime getCriadaEm() { return criadaEm; }

    public String getModalidade() { return modalidade; }
    public void setModalidade(String modalidade) { this.modalidade = modalidade; }

    public String getUrlOrigem() { return urlOrigem; }
    public void setUrlOrigem(String urlOrigem) { this.urlOrigem = urlOrigem; }

    public String getMetodoHttp() { return metodoHttp; }
    public void setMetodoHttp(String metodoHttp) { this.metodoHttp = metodoHttp; }

    public String getCabecalhos() { return cabecalhos; }
    public void setCabecalhos(String cabecalhos) { this.cabecalhos = cabecalhos; }

    public String getCredencialRef() { return credencialRef; }
    public void setCredencialRef(String credencialRef) { this.credencialRef = credencialRef; }

    public String getCorpoRequisicao() { return corpoRequisicao; }
    public void setCorpoRequisicao(String corpoRequisicao) { this.corpoRequisicao = corpoRequisicao; }

    public String getPaginacao() { return paginacao; }
    public void setPaginacao(String paginacao) { this.paginacao = paginacao; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public OffsetDateTime getColetaSolicitadaEm() { return coletaSolicitadaEm; }
    public void setColetaSolicitadaEm(OffsetDateTime coletaSolicitadaEm) { this.coletaSolicitadaEm = coletaSolicitadaEm; }

    public OffsetDateTime getUltimaColetaEm() { return ultimaColetaEm; }

    public String getUltimaColetaMensagem() { return ultimaColetaMensagem; }
}