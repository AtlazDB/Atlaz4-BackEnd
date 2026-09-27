-- A versão do conjunto passa a existir só no banco (ainda sem arquivo na zona tratada)
ALTER TABLE dataset_versao MODIFY (chave_objeto NULL);

-- Atributos do CAR que vamos exibir
ALTER TABLE imovel_rural ADD (situacao VARCHAR2(20 CHAR));