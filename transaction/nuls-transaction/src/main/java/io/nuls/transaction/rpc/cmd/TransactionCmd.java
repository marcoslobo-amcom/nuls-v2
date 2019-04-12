package io.nuls.transaction.rpc.cmd;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.nuls.base.data.NulsDigestData;
import io.nuls.base.data.Transaction;
import io.nuls.rpc.cmd.BaseCmd;
import io.nuls.rpc.model.CmdAnnotation;
import io.nuls.rpc.model.Parameter;
import io.nuls.rpc.model.message.Response;
import io.nuls.rpc.util.RPCUtil;
import io.nuls.tools.core.annotation.Autowired;
import io.nuls.tools.core.annotation.Component;
import io.nuls.tools.exception.NulsException;
import io.nuls.tools.model.ObjectUtils;
import io.nuls.tools.parse.JSONUtils;
import io.nuls.transaction.constant.TxCmd;
import io.nuls.transaction.constant.TxConstant;
import io.nuls.transaction.constant.TxErrorCode;
import io.nuls.transaction.manager.ChainManager;
import io.nuls.transaction.manager.TxManager;
import io.nuls.transaction.model.bo.Chain;
import io.nuls.transaction.model.bo.TxPackage;
import io.nuls.transaction.model.bo.TxRegister;
import io.nuls.transaction.model.bo.VerifyTxResult;
import io.nuls.transaction.model.dto.ModuleTxRegisterDTO;
import io.nuls.transaction.model.dto.TxRegisterDTO;
import io.nuls.transaction.model.po.TransactionConfirmedPO;
import io.nuls.transaction.service.ConfirmedTxService;
import io.nuls.transaction.service.TxService;
import io.nuls.transaction.utils.TxUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.transaction.utils.LoggerUtil.Log;

/**
 * @author: Charlie
 * @date: 2018/11/12
 */
@Component
public class TransactionCmd extends BaseCmd {

    @Autowired
    private TxService txService;
    @Autowired
    private ConfirmedTxService confirmedTxService;
    @Autowired
    private ChainManager chainManager;


    /**
     * Register module transactions, validators, processors(commit, rollback), etc.
     * 注册模块交易
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_REGISTER, version = 1.0, description = "module transaction registration")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "moduleCode", parameterType = "String")
    @Parameter(parameterName = "moduleValidator", parameterType = "String")
    @Parameter(parameterName = "commit", parameterType = "String")
    @Parameter(parameterName = "rollback", parameterType = "String")
    @Parameter(parameterName = "list", parameterType = "List")
    public Response register(Map params) {
        Map<String, Boolean> map = new HashMap<>(TxConstant.INIT_CAPACITY_2);
        boolean result = false;
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("moduleCode"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("moduleValidator"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("commit"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("rollback"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("list"), TxErrorCode.PARAMETER_ERROR.getMsg());

            JSONUtils.getInstance().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            ModuleTxRegisterDTO moduleTxRegisterDto = JSONUtils.json2pojo(JSONUtils.obj2json(params), ModuleTxRegisterDTO.class);

            chain = chainManager.getChain(moduleTxRegisterDto.getChainId());
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<TxRegisterDTO> txRegisterList = moduleTxRegisterDto.getList();
            if (moduleTxRegisterDto == null || txRegisterList == null) {
                throw new NulsException(TxErrorCode.NULL_PARAMETER);
            }
            //循环注册多种交易
            for (TxRegisterDTO txRegisterDto : txRegisterList) {
                TxRegister txRegister = new TxRegister();
                txRegister.setModuleCode(moduleTxRegisterDto.getModuleCode());
                txRegister.setModuleValidator(moduleTxRegisterDto.getModuleValidator());
                txRegister.setTxType(txRegisterDto.getTxType());
                txRegister.setValidator(txRegisterDto.getValidator());
                txRegister.setCommit(moduleTxRegisterDto.getCommit());
                txRegister.setRollback(moduleTxRegisterDto.getRollback());
                txRegister.setSystemTx(txRegisterDto.getSystemTx());
                txRegister.setUnlockTx(txRegisterDto.getUnlockTx());
                txRegister.setVerifySignature(txRegisterDto.getVerifySignature());

                result = txService.register(chain, txRegister);
            }

        } catch (IOException e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.IO_ERROR);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }

        map.put("value", result);
        return success(map);
    }

    /**
     * Receive a new transaction serialization entity
     * 接收本地新交易
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_NEWTX, version = 1.0, description = "receive a new transaction")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "tx", parameterType = "String")
    public Response newTx(Map params) {

        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("tx"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            String txStr = (String) params.get("tx");
            //将txStr转换为Transaction对象
            Transaction transaction = TxUtil.getInstanceRpcStr(txStr, Transaction.class);
            //将交易放入待验证本地交易队列中
            if(!txService.newTx(chain, transaction)){
                return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
            }
            Map<String, Boolean> map = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            map.put("value", true);
            return success(map);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * 新交易基础验证
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_BASE_VALIDATE, version = 1.0, description = "baseValidateTx")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "tx", parameterType = "String")
    public Response baseValidateTx(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("tx"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            String txStr = (String) params.get("tx");
            //将txStr转换为Transaction对象
            Transaction tx = TxUtil.getInstanceRpcStr(txStr, Transaction.class);
            TxRegister txRegister = TxManager.getTxRegister(chain, tx.getType());
            //将交易放入待验证本地交易队列中
            txService.baseValidateTx(chain, tx, txRegister);
            Map<String, Boolean> map = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            map.put("value", true);
            return success(map);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * Extract a packaged transaction list based on the packaging end time and transactions total size
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_PACKABLETXS, version = 1.0, description = "returns a list of packaged transactions")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "endTimestamp", parameterType = "long")
    @Parameter(parameterName = "maxTxDataSize", parameterType = "int")
    @Parameter(parameterName = "blockTime", parameterType = "long")
    @Parameter(parameterName = "packingAddress", parameterType = "String")
    @Parameter(parameterName = "preStateRoot", parameterType = "String")
    public Response packableTxs(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("endTimestamp"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("maxTxDataSize"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("blockTime"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("packingAddress"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("preStateRoot"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            //结束打包的时间
            long endTimestamp = (long) params.get("endTimestamp");
            //交易数据最大容量值
            int maxTxDataSize = (int) params.get("maxTxDataSize");

            long blockHeight = chain.getBestBlockHeight() + 1;
            long blockTime = (long) params.get("blockTime");
            String packingAddress = (String) params.get("packingAddress");
            String preStateRoot = (String) params.get("preStateRoot");

            TxPackage txPackage = txService.getPackableTxs(chain, endTimestamp, maxTxDataSize, blockHeight, blockTime, packingAddress, preStateRoot);
            Map<String, Object> map = new HashMap<>(TxConstant.INIT_CAPACITY_4);
            map.put("list", txPackage.getList());
            map.put("stateRoot", txPackage.getStateRoot());
            map.put("packageHeight", txPackage.getPackageHeight());
            return success(map);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * Save the transaction in the new block that was verified to the database
     * 保存新区块的交易
     *
     * @param params Map
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_SAVE, version = 1.0, description = "transaction save")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHashList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    public Response txSave(Map params) {
        Map<String, Boolean> map = new HashMap<>(TxConstant.INIT_CAPACITY_16);
        boolean result = false;
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHashList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("blockHeader"), TxErrorCode.PARAMETER_ERROR.getMsg());

            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<String> txHashStrList = (List<String>) params.get("txHashList");
            List<NulsDigestData> txHashList = new ArrayList<>();
            //将交易hashHex解码为交易hash字节数组
            for (String hashStr : txHashStrList) {
                txHashList.add(NulsDigestData.fromDigestHex(hashStr));
            }
            result = confirmedTxService.saveTxList(chain, txHashList, (String) params.get("blockHeader"));
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
        Map<String, Boolean> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
        resultMap.put("value", result);
        return success(resultMap);
    }

    /**
     * Save the transaction in the new block that was verified to the database
     * 保存创世块的交易, 接收完整交易
     *
     * @param params Map
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GENGSIS_SAVE, version = 1.0, description = "transaction save")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    public Response txGengsisSave(Map params) {
        Map<String, Boolean> map = new HashMap<>(TxConstant.INIT_CAPACITY_16);
        boolean result = false;
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("blockHeader"), TxErrorCode.PARAMETER_ERROR.getMsg());

            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<String> txStrList = (List<String>) params.get("txList");
            List<Transaction> txList = new ArrayList<>();
            for (String tx : txStrList) {
                txList.add(TxUtil.getInstanceRpcStr(tx, Transaction.class));
            }
            result = confirmedTxService.saveGengsisTxList(chain, txList, (String) params.get("blockHeader"));
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
        Map<String, Boolean> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
        resultMap.put("value", result);
        return success(resultMap);
    }

    /**
     * rollback the transaction in the new block that was verified to the database
     * 回滚新区块的交易
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_ROLLBACK, version = 1.0, description = "transaction rollback")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHashList", parameterType = "List")
    @Parameter(parameterName = "blockHeader", parameterType = "String")
    public Response txRollback(Map params) {
        boolean result;
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHashList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("blockHeader"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<String> txHashStrList = (List<String>) params.get("txHashList");
            List<NulsDigestData> txHashList = new ArrayList<>();
            //将交易hashHex解码为交易hash字节数组
            for (String hashStr : txHashStrList) {
                txHashList.add(NulsDigestData.fromDigestHex(hashStr));
            }
            //批量回滚已确认交易
            result = confirmedTxService.rollbackTxList(chain, txHashList, (String) params.get("blockHeader"));
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
        Map<String, Boolean> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
        resultMap.put("value", result);
        return success(resultMap);
    }

    /**
     * Get system transaction types
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GET_SYSTEM_TYPES, version = 1.0, description = "Get system transaction types")
    @Parameter(parameterName = "chainId", parameterType = "int")
    public Response getSystemTypes(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<Integer> list = TxManager.getSysTypes(chain);
            Map<String, Object> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            resultMap.put("list", list);
            return success(resultMap);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * 根据hash获取交易, 先查未确认, 查不到再查已确认
     * Get the transaction that have been packaged into the block from the database
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GETTX, version = 1.0, description = "Get transaction ")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHash", parameterType = "String")
    public Response getTx(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHash"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            String txHash = (String) params.get("txHash");
            if (!NulsDigestData.validHash(txHash)) {
                throw new NulsException(TxErrorCode.HASH_ERROR);
            }
            TransactionConfirmedPO tx = txService.getTransaction(chain, NulsDigestData.fromDigestHex(txHash));
            Map<String, String> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            if (tx == null) {
                Log.debug("getTx - from all, fail! tx is null, txHash:{}", txHash);
                resultMap.put("tx", null);
            } else {
                Log.debug("getTx - from all, success txHash : " + tx.getTx().getHash().getDigestHex());
                resultMap.put("tx", RPCUtil.encode(tx.getTx().serialize()));
            }
            return success(resultMap);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * 根据hash获取已确认交易(只查已确认)
     * Get the transaction that have been packaged into the block from the database
     *
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GET_CONFIRMED_TX, version = 1.0, description = "Get transaction ")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHash", parameterType = "String")
    public Response getConfirmedTx(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHash"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            String txHash = (String) params.get("txHash");
            if (!NulsDigestData.validHash(txHash)) {
                throw new NulsException(TxErrorCode.HASH_ERROR);
            }
            TransactionConfirmedPO tx = confirmedTxService.getConfirmedTransaction(chain, NulsDigestData.fromDigestHex(txHash));
            Map<String, String> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            if (tx == null) {
                Log.debug("getConfirmedTransaction fail, tx is null. txHash:{}", txHash);
                resultMap.put("tx", null);
            } else {
                Log.debug("getConfirmedTransaction success. txHash:{}", txHash);
                resultMap.put("tx", RPCUtil.encode(tx.getTx().serialize()));
            }
            return success(resultMap);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * 根据交易hash list 获取区块的完整交易
     * 如果没有查询到,或者查询到的不是区块完整的交易数据 则返回空list
     * @param params
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GET_BLOCK_TXS, version = 1.0, description = "Get block transactions ")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHashList", parameterType = "list")
    public Response getBlockTxs(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHashList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<String> txHashList = (List<String>) params.get("txHashList");
            List<String> txList = confirmedTxService.getTxList(chain,txHashList);
            Map<String, List<String>> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            resultMap.put("txList", txList);
            return success(resultMap);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

    /**
     * 根据hash列表,批量获取交易, 先查未确认,再查已确认
     * @param params allHits 为true时必须全部查到才返回数据, 否则返回空list. false: 查到几个返回几个
     * @return Response
     */
    @CmdAnnotation(cmd = TxCmd.TX_GET_BLOCK_TXS_EXTEND, version = 1.0, description = "Get block transactions incloud unconfirmed ")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "txHashList", parameterType = "list")
    @Parameter(parameterName = "allHits", parameterType = "boolean")
    public Response getBlockTxsExtend(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txHashList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("allHits"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            List<String> txHashList = (List<String>) params.get("txHashList");
            boolean allHits = (boolean) params.get("allHits");
            List<String> txList = confirmedTxService.getTxListExtend(chain, txHashList, allHits);
            Map<String, List<String>> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
            resultMap.put("txList", txList);
            return success(resultMap);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }


    /**
     * The transaction is verified locally before the block is saved,
     * including calling the validator, verifying the coinData, etc.
     * If it is a cross-chain transaction and not initiated by the current chain,
     * the result of the cross-chain verification is checked.
     *
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = TxCmd.TX_BATCHVERIFY, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "height", parameterType = "long")
    @Parameter(parameterName = "txList", parameterType = "List")
    @Parameter(parameterName = "blockTime", parameterType = "long")
    @Parameter(parameterName = "packingAddress", parameterType = "String")
    @Parameter(parameterName = "stateRoot", parameterType = "String")
    @Parameter(parameterName = "preStateRoot", parameterType = "String")
    public Response batchVerify(Map params) {
        VerifyTxResult verifyTxResult = null;
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("height"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("txList"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("blockTime"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("packingAddress"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("stateRoot"), TxErrorCode.PARAMETER_ERROR.getMsg());
            ObjectUtils.canNotEmpty(params.get("preStateRoot"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            Long height = Long.valueOf(params.get("height").toString());
            List<String> txList = (List<String>)  params.get("txList");

            long blockTime = (long) params.get("blockTime");
            String packingAddress = (String) params.get("packingAddress");
            String stateRoot = (String) params.get("stateRoot");
            String preStateRoot = (String) params.get("preStateRoot");

            verifyTxResult = txService.batchVerify(chain, txList, height, blockTime, packingAddress, stateRoot, preStateRoot);
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
        Map<String, Object> resultMap = new HashMap<>(TxConstant.INIT_CAPACITY_2);
        boolean result = verifyTxResult.success();
        resultMap.put("value", result);
        return success(resultMap);
    }


    /**
     * 节点是否正在打包(由共识调用), 决定了新交易是否放入交易模块的待打包队列
     *
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = TxCmd.TX_CS_STATE, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "packaging", parameterType = "Boolean")
    public Response packaging(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            Boolean packaging = null == params.get("packaging") ? null : (Boolean) params.get("packaging");
            if (null == packaging) {
                throw new NulsException(TxErrorCode.PARAMETER_ERROR);
            }
            chain.getPackaging().set(packaging);
            chain.getLoggerMap().get(TxConstant.LOG_TX).debug("Task-Packaging 节点是否是打包节点,状态变更为: {}", chain.getPackaging().get());
            return success();
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }


    /**
     * 最新区块高度
     * @param params
     * @return
     */
    @CmdAnnotation(cmd = TxCmd.TX_BLOCK_HEIGHT, version = 1.0, description = "")
    @Parameter(parameterName = "chainId", parameterType = "int")
    @Parameter(parameterName = "height", parameterType = "long")
    public Response height(Map params) {
        Chain chain = null;
        try {
            ObjectUtils.canNotEmpty(params.get("chainId"), TxErrorCode.PARAMETER_ERROR.getMsg());
            chain = chainManager.getChain((int) params.get("chainId"));
            if (null == chain) {
                throw new NulsException(TxErrorCode.CHAIN_NOT_FOUND);
            }
            Long height = Long.valueOf(params.get("height").toString());
            chain.setBestBlockHeight(height);
            //todo
            //confirmedTxService.processEffectCrossTx(chain, height);
            return success();
        } catch (NulsException e) {
            errorLogProcess(chain, e);
            return failed(e.getErrorCode());
        } catch (Exception e) {
            errorLogProcess(chain, e);
            return failed(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }



    private void errorLogProcess(Chain chain, Exception e) {
        if (chain == null) {
            Log.error(e);
        } else {
            chain.getLoggerMap().get(TxConstant.LOG_TX).error(e);
        }
    }


}
