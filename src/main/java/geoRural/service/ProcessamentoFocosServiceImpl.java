package geoRural.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ProcessamentoEmAndamentoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;
import geoRural.service.GravadorImoveis.VersaoCriada;
import geoRural.service.ValidadorFoco.Resultado;

/**
 * Focos de calor do INPE. Mesmo caminho dos imóveis, com uma diferença: o arquivo traz o Brasil
 * inteiro, e o que é de fora do Paraná é só contado, não vai para a quarentena.
 */
@Service
public class ProcessamentoFocosServiceImpl implements ProcessamentoFocosService {

    public static final String SIGLA = "INPE-FOCOS";

    private static final Logger log = LoggerFactory.getLogger(ProcessamentoFocosServiceImpl.class);

    static final int TAMANHO_LOTE = 1000;

    private final GravadorImoveis gravador;
    private final GravadorFocos gravadorFocos;
    private final LeitorFocosInpe leitor;
    private final ObjectStorageService objectStorage;
    private final TransactionTemplate transacao;

    public ProcessamentoFocosServiceImpl(GravadorImoveis gravador, GravadorFocos gravadorFocos,
                                         LeitorFocosInpe leitor, ObjectStorageService objectStorage,
                                         TransactionTemplate transacao) {
        this.gravador = gravador;
        this.gravadorFocos = gravadorFocos;
        this.leitor = leitor;
        this.objectStorage = objectStorage;
        this.transacao = transacao;
    }

    @Override
    public ProcessamentoResponse processar(Long arquivoId) {
        ArquivoParaProcessar arquivo = gravador.buscarArquivo(arquivoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

        String nome = arquivo.nomeOriginal().toLowerCase();
        if (!SIGLA.equalsIgnoreCase(arquivo.siglaFonte()) || !(nome.endsWith(".csv") || nome.endsWith(".zip"))) {
            throw new RequisicaoInvalidaException(
                    "Só arquivos .csv (ou .csv compactado em .zip) da fonte INPE-FOCOS podem ser processados aqui.");
        }
        if (!gravador.marcarProcessando(arquivoId)) {
            throw new ProcessamentoEmAndamentoException(arquivoId);
        }

        Long execucaoId = null;
        Path baixado = null;
        try {
            execucaoId = gravador.iniciarExecucao(arquivoId);
            baixado = objectStorage.baixar(arquivo.chaveObjeto());

            long execucao = execucaoId;
            Path csv = baixado;
            ProcessamentoResponse resposta = transacao.execute(status -> gravar(arquivoId, execucao, csv));
            log.info("Focos do arquivo {} processados: {}", arquivoId, resposta);
            return resposta;
        } catch (Exception e) {
            log.error("Falha ao processar os focos do arquivo {}", arquivoId, e);
            String mensagem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            gravador.falhar(arquivoId, execucaoId, mensagem);
            return ProcessamentoResponse.rejeitado(arquivoId, execucaoId, mensagem);
        } finally {
            apagar(baixado);
        }
    }

    private ProcessamentoResponse gravar(long arquivoId, long execucaoId, Path csv) {
        VersaoCriada versao = gravador.criarVersao(execucaoId, GravadorFocos.CONJUNTO);
        ValidadorFoco validador = new ValidadorFoco();
        Contagem contagem = new Contagem(versao.id(), execucaoId);

        leitor.ler(csv, foco -> contagem.adicionar(validador.validar(foco)));
        contagem.descarregar();

        int novos = gravadorFocos.contarNovos(versao.id());
        log.info("{} focos do Paraná no arquivo, {} novos", contagem.validos, novos);

        // registros da versão = focos novos; os repetidos já estão contados na versão de origem
        gravador.concluir(arquivoId, execucaoId, versao.id(), contagem.lidos, contagem.validos, contagem.invalidos);
        gravador.atualizarRegistrosDaVersao(versao.id(), novos);
        return ProcessamentoResponse.processado(arquivoId, execucaoId, versao.versao(),
                contagem.lidos, contagem.validos, contagem.invalidos, contagem.foraDoRecorte);
    }

    private class Contagem {
        final long versaoId;
        final long execucaoId;
        final List<Resultado> aprovados = new ArrayList<>(TAMANHO_LOTE);
        final List<Resultado> reprovados = new ArrayList<>(TAMANHO_LOTE);
        int lidos;
        int validos;
        int invalidos;
        int foraDoRecorte;

        Contagem(long versaoId, long execucaoId) {
            this.versaoId = versaoId;
            this.execucaoId = execucaoId;
        }

        void adicionar(Resultado resultado) {
            lidos++;
            if (resultado.foraDoRecorte()) {
                foraDoRecorte++;
            } else if (resultado.aprovado()) {
                validos++;
                aprovados.add(resultado);
            } else {
                invalidos++;
                reprovados.add(resultado);
            }
            if (aprovados.size() >= TAMANHO_LOTE || reprovados.size() >= TAMANHO_LOTE) {
                descarregar();
            }
            if (lidos % 100_000 == 0) {
                log.info("{} linhas lidas", lidos);
            }
        }

        void descarregar() {
            gravadorFocos.gravar(versaoId, aprovados);
            gravadorFocos.gravarQuarentena(execucaoId, reprovados);
            aprovados.clear();
            reprovados.clear();
        }
    }

    private static void apagar(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (Exception e) {
            log.warn("Não foi possível apagar o temporário {}", arquivo, e);
        }
    }
}
