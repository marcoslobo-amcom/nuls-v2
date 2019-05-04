package io.nuls.transaction.rpc.call;

import io.nuls.base.basic.AddressTool;
import io.nuls.base.data.MultiSigAccount;
import io.nuls.rpc.info.Constants;
import io.nuls.rpc.model.ModuleE;
import io.nuls.tools.exception.NulsException;
import io.nuls.tools.log.Log;
import io.nuls.transaction.constant.TxConstant;
import io.nuls.transaction.constant.TxErrorCode;
import io.nuls.transaction.utils.TxUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用其他模块跟交易相关的接口
 *
 * @author: qinyifeng
 * @date: 2018/12/20
 */
public class AccountCall {

    /**
     * 查询多签账户
     * Query multi-sign account
     *
     * @param multiSignAddress
     * @return
     */
    public static MultiSigAccount getMultiSigAccount(byte[] multiSignAddress) throws NulsException {
        try {
            String address = AddressTool.getStringAddressByBytes(multiSignAddress);
            int chainId = AddressTool.getChainIdByAddress(address);
            Map<String, Object> params = new HashMap<>(TxConstant.INIT_CAPACITY_8);
            params.put(Constants.VERSION_KEY_STR, TxConstant.RPC_VERSION);
            params.put("chainId", chainId);
            params.put("address", address);
            HashMap result = (HashMap) TransactionCall.request(ModuleE.AC.abbr, "ac_getMultiSigAccount", params);
            String mAccountStr = (String) result.get("value");
            return null == mAccountStr ? null : TxUtil.getInstanceRpcStr(mAccountStr, MultiSigAccount.class);
        } catch (RuntimeException e){
            Log.error(e);
            throw new NulsException(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }

}