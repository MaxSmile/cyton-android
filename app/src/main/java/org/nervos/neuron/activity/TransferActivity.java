package org.nervos.neuron.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.BottomSheetDialog;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.AppCompatEditText;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.yanzhenjie.permission.AndPermission;
import com.yanzhenjie.permission.Permission;

import org.nervos.appchain.protocol.core.methods.response.AppSendTransaction;
import org.nervos.neuron.R;
import org.nervos.neuron.item.ChainItem;
import org.nervos.neuron.item.CurrencyItem;
import org.nervos.neuron.item.TokenItem;
import org.nervos.neuron.item.TransactionInfo;
import org.nervos.neuron.item.WalletItem;
import org.nervos.neuron.service.EthRpcService;
import org.nervos.neuron.service.HttpUrls;
import org.nervos.neuron.service.AppChainRpcService;
import org.nervos.neuron.service.NeuronSubscriber;
import org.nervos.neuron.service.TokenService;
import org.nervos.neuron.service.WalletService;
import org.nervos.neuron.util.AddressUtil;
import org.nervos.neuron.util.Blockies;
import org.nervos.neuron.util.ConstUtil;
import org.nervos.neuron.util.CurrencyUtil;
import org.nervos.neuron.util.NumberUtil;
import org.nervos.neuron.util.QRUtils.CodeUtils;
import org.nervos.neuron.util.SensorDataTrackUtils;
import org.nervos.neuron.util.db.DBChainUtil;
import org.nervos.neuron.util.db.DBWalletUtil;
import org.nervos.neuron.util.db.SharePrefUtil;
import org.nervos.neuron.util.permission.PermissionUtil;
import org.nervos.neuron.util.permission.RuntimeRationale;
import org.nervos.neuron.view.TitleBar;
import org.nervos.neuron.view.button.CommonButton;
import org.nervos.neuron.view.dialog.TransferDialog;
import org.nervos.neuron.view.tool.NeuronTextWatcher;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Convert;

import java.math.BigInteger;

import de.hdodenhof.circleimageview.CircleImageView;
import rx.Subscriber;

import static org.web3j.utils.Convert.Unit.GWEI;

public class TransferActivity extends NBaseActivity {

    private static final int REQUEST_CODE_SCAN = 0x01;
    public static final String EXTRA_TOKEN = "extra_token";
    public static final String EXTRA_ADDRESS = "extra_address";

    private TextView walletAddressText, walletNameText, feeValueText, balanceText;
    private ImageView scanImage;
    private AppCompatEditText receiveAddressEdit, transferValueEdit;
    private CommonButton nextActionButton;
    private CircleImageView photoImage;
    private ProgressBar progressBar;

    private WalletItem walletItem;
    private TokenItem tokenItem;
    private BigInteger mGasPrice, mGasLimit = ConstUtil.GAS_LIMIT, mQuota, mGas;
    private double mTokenPrice = 0.0f, mTokenBalance, mNativeTokenBalance;
    private CurrencyItem currencyItem;
    private TitleBar titleBar;
    private double mTransferFee;

    private TransferDialog transferDialog;

    @Override
    protected int getContentLayout() {
        return R.layout.activity_transfer;
    }

    @Override
    protected void initView() {
        scanImage = findViewById(R.id.transfer_address_scan);
        nextActionButton = findViewById(R.id.next_action_button);
        walletAddressText = findViewById(R.id.wallet_address);
        walletNameText = findViewById(R.id.wallet_name);
        balanceText = findViewById(R.id.wallet_balance_text);
        feeValueText = findViewById(R.id.fee_value_text);
        receiveAddressEdit = findViewById(R.id.transfer_address);
        transferValueEdit = findViewById(R.id.transfer_value);
        photoImage = findViewById(R.id.wallet_photo);
        titleBar = findViewById(R.id.title);

    }

    @Override
    protected void initData() {
        initWalletData();
        initTokenBalance();
        initTransferFee();
    }

    private void initWalletData() {
        tokenItem = getIntent().getParcelableExtra(EXTRA_TOKEN);
        titleBar.setTitle(tokenItem.symbol + getString(R.string.title_transfer));

        EthRpcService.init(mActivity);
        AppChainRpcService.init(mActivity, HttpUrls.APPCHAIN_NODE_IP);

        walletItem = DBWalletUtil.getCurrentWallet(this);
        walletAddressText.setText(walletItem.address);
        walletNameText.setText(walletItem.name);

        String address = getIntent().getStringExtra(EXTRA_ADDRESS);
        if (!TextUtils.isEmpty(address)) {
            receiveAddressEdit.setText(address);
        }

        photoImage.setImageBitmap(Blockies.createIcon(walletItem.address));
    }



    @SuppressLint("SetTextI18n")
    private void initTokenBalance() {
        WalletService.getBalanceWithToken(mActivity, tokenItem).subscribe(new NeuronSubscriber<Double>() {
            @Override
            public void onNext(Double balance) {
                mTokenBalance = balance;
                balanceText.setText(String.format(
                        getString(R.string.transfer_balance_place_holder),
                        NumberUtil.getDecimal8Sub(balance) + " " + tokenItem.symbol));
            }
        });

        WalletService.getBalanceWithNativeToken(mActivity, tokenItem).subscribe(new Subscriber<Double>(){
            @Override
            public void onNext(Double balance) {
                mNativeTokenBalance = balance;
                initBalanceToTransferView();
            }
            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }
            @Override
            public void onCompleted() {

            }
        });

    }

    private void initBalanceToTransferView() {
        balanceText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(tokenItem.contractAddress)) {
                    transferValueEdit.setText(String.valueOf(mNativeTokenBalance - mTransferFee));
                } else {
                    transferValueEdit.setText(String.valueOf(mTokenBalance));
                }
            }
        });
    }

    private void initTransferFee() {
        if (isETH()) {
            initGasInfo();
            initTransferEditValue();
            initTokenPrice();
        } else {
            initQuota();
        }
    }

    private void initGasInfo() {
        showProgressCircle();
        EthRpcService.getEthGasPrice().subscribe(new NeuronSubscriber<BigInteger>() {
            @Override
            public void onError(Throwable e) {
                Toast.makeText(mActivity, e.getMessage(), Toast.LENGTH_SHORT).show();
                dismissProgressCircle();
            }
            @Override
            public void onNext(BigInteger gasPrice) {
                dismissProgressCircle();
                mGasPrice = gasPrice;
                mGas = mGasPrice.multiply(mGasLimit);
                initTransferFeeView();
            }
        });
    }


    /**
     * get the price of token
     */
    private void initTokenPrice() {
        currencyItem = CurrencyUtil.getCurrencyItem(mActivity);
        TokenService.getCurrency(tokenItem.symbol, currencyItem.getName())
            .subscribe(new NeuronSubscriber<String>() {
                @Override
                public void onNext(String price) {
                    if (TextUtils.isEmpty(price)) return;
                    try {
                        mTokenPrice = Double.parseDouble(price);
                        initTransferFeeView();
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            });
    }



    @SuppressLint("SetTextI18n")
    private void initTransferFeeView() {
        mTransferFee = NumberUtil.getEthFromWei(mGas);
        if (mTokenPrice > 0 && currencyItem != null) {
            feeValueText.setText(NumberUtil.getDecimal8ENotation(mTransferFee)
                    + getFeeTokenUnit() + " = " + currencyItem.getSymbol()
                    + NumberUtil.getDecimalValid_2(mTransferFee * mTokenPrice));
        }
    }


    /**
     * handle transfer advanced setup
     */
    private void initAdvancedSetupDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(mActivity);
        View view = getLayoutInflater().inflate(R.layout.dialog_advance_setup, null);
        EditText gasPriceEdit = view.findViewById(R.id.edit_gas_price);
        TextView gasPriceDefaultText = view.findViewById(R.id.default_gas_price_text);
        TextView gasLimitDefaultText = view.findViewById(R.id.default_gas_limit_text);
        CommonButton confirmButton = view.findViewById(R.id.advanced_setup_button);
        view.findViewById(R.id.close_layout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        String gasPriceDefault = NumberUtil.getDecimalValid_2(
                Convert.fromWei(mGasPrice.toString(), GWEI).doubleValue());
        gasPriceDefaultText.setText(
                String.format(getString(R.string.default_eth_gas_price), gasPriceDefault));
        gasLimitDefaultText.setText(mGasLimit.toString());

        confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String gasPrice = gasPriceEdit.getText().toString().trim();
                if (TextUtils.isEmpty(gasPrice)) {
                    Toast.makeText(mActivity, R.string.input_correct_gas_price_tip, Toast.LENGTH_SHORT).show();
                    return;
                } else if(Double.parseDouble(gasPrice) < Double.parseDouble(gasPriceDefault)) {
                    Toast.makeText(mActivity,
                            String.format(getString(R.string.gas_price_too_low), gasPriceDefault),
                            Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    mGas = Convert.toWei(gasPrice, GWEI).toBigInteger()
                            .multiply(mGasLimit);
                    initTransferFeeView();
                }
                dialog.dismiss();
            }
        });
        dialog.setContentView(view);
        dialog.show();
    }

    @SuppressLint("SetTextI18n")
    private void initQuota() {
        mQuota = TextUtils.isEmpty(tokenItem.contractAddress) ?
                ConstUtil.QUOTA_TOKEN : ConstUtil.QUOTA_ERC20;
        mTransferFee = NumberUtil.getEthFromWei(mQuota);
        feeValueText.setText(NumberUtil.getDecimal8ENotation(mTransferFee) + getFeeTokenUnit());
    }

    @Override
    protected void initAction() {
        scanImage.setOnClickListener((view) -> {
            AndPermission.with(mActivity)
                    .runtime().permission(Permission.Group.CAMERA)
                    .rationale(new RuntimeRationale())
                    .onGranted(permissions -> {
                        Intent intent = new Intent(mActivity, QrCodeActivity.class);
                        startActivityForResult(intent, REQUEST_CODE_SCAN);
                    })
                    .onDenied(permissions -> PermissionUtil.showSettingDialog(mActivity, permissions))
                    .start();
        });

        nextActionButton.setOnClickListener(v -> {
            if (TextUtils.isEmpty(receiveAddressEdit.getText().toString().trim())) {
                Toast.makeText(mActivity, R.string.transfer_address_not_null, Toast.LENGTH_SHORT).show();
            } else if (receiveAddressEdit.getText().toString().trim().equalsIgnoreCase(walletItem.address)) {
                Toast.makeText(mActivity, R.string.address_from_equal_to, Toast.LENGTH_LONG).show();
            } else if (!AddressUtil.isAddressValid(receiveAddressEdit.getText().toString().trim())) {
                Toast.makeText(mActivity, R.string.address_error, Toast.LENGTH_LONG).show();
            } else if (TextUtils.isEmpty(transferValueEdit.getText().toString().trim())) {
                Toast.makeText(mActivity, R.string.transfer_amount_not_null, Toast.LENGTH_SHORT).show();
            } else if (mNativeTokenBalance < mTransferFee) {
                Toast.makeText(mActivity, String.format(getString(R.string.balance_not_enough_fee),
                        tokenItem.symbol), Toast.LENGTH_SHORT).show();
            } else if (checkTransferValueMoreBalance()) {
                new AlertDialog.Builder(mActivity).setMessage(R.string.all_balance_transfer_tip)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                getConfirmTransferView();
                            }
                        }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        }).create().show();
            } else {
                getConfirmTransferView();
            }
        });
    }

    private boolean checkTransferValueMoreBalance() {
        if (isNativeToken()) {
            return Double.parseDouble(transferValueEdit.getText().toString().trim()) >
                    (mNativeTokenBalance - mTransferFee);
        } else {
            return Double.parseDouble(transferValueEdit.getText().toString().trim()) > mTokenBalance;
        }
    }

    private boolean isAddressOK = false, isValueOk = false;
    private void initTransferEditValue() {
        receiveAddressEdit.addTextChangedListener(new NeuronTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                super.afterTextChanged(s);
                isAddressOK = !TextUtils.isEmpty(s);
                if (isAddressOK && isValueOk && isETHERC20()) {
                    initGasLimit();
                }
            }
        });
        transferValueEdit.addTextChangedListener(new NeuronTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                super.afterTextChanged(s);
                isValueOk = !TextUtils.isEmpty(s);
                if (isAddressOK && isValueOk && isETHERC20()) {
                    initGasLimit();
                }
            }
        });
    }


    private void initGasLimit() {
        initAdvancedSetup();

        TransactionInfo transactionInfo = new TransactionInfo(tokenItem.contractAddress, "0");
        transactionInfo.data = EthRpcService.createTokenTransferData(
                receiveAddressEdit.getText().toString(),
                Convert.toWei(transferValueEdit.getText().toString(), Convert.Unit.ETHER).toBigInteger());
        EthRpcService.getEthGasLimit(transactionInfo).subscribe(new NeuronSubscriber<BigInteger>() {
            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }

            @Override
            public void onNext(BigInteger gasLimit) {
                mGasLimit = gasLimit.multiply(BigInteger.valueOf(2));
                mGas = mGasPrice.multiply(mGasLimit);
                initTransferFeeView();
            }
        });
    }

    private void initAdvancedSetup() {
        if (isETH()) {
            feeValueText.setTextColor(ContextCompat.getColor(mActivity, R.color.colorPrimary));
            feeValueText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    initAdvancedSetupDialog();
                }
            });
        }
    }

    private boolean isETH() {
        return tokenItem.chainId < 0;
    }

    private boolean isETHERC20() {
        return tokenItem.chainId < 0 && !TextUtils.isEmpty(tokenItem.contractAddress);
    }

    private boolean isNativeToken() {
       return TextUtils.isEmpty(tokenItem.contractAddress);
    }

    private String getFeeTokenUnit() {
        if (isETH()) {
            return " " + ConstUtil.ETH;
        } else {
            ChainItem chainItem = DBChainUtil.getChain(mActivity, tokenItem.chainId);
            return chainItem == null ? "" : " " + chainItem.tokenSymbol;
        }
    }

    /**
     * struct confirm transfer view
     *
     * @return
     */
    @SuppressLint("SetTextI18n")
    private void getConfirmTransferView() {
        if (isFastDoubleClick()) return;
        String value = transferValueEdit.getText().toString().trim();
        transferDialog = new TransferDialog(this, (password, progressBar) -> {
            this.progressBar = progressBar;
            if (TextUtils.isEmpty(password)) {
                Toast.makeText(mActivity, R.string.password_not_null, Toast.LENGTH_SHORT).show();
            } else if (!WalletService.checkPassword(mActivity, password, walletItem)) {
                Toast.makeText(mActivity, R.string.password_fail, Toast.LENGTH_SHORT).show();
            } else {
                transferDialog.setButtonClickAble(false);
                progressBar.setVisibility(View.VISIBLE);
                if (isETH()) {
                    SensorDataTrackUtils.transferAccount(tokenItem.symbol, value, receiveAddressEdit.getText().toString().trim(), walletItem.address, ConstUtil.ETH, "2");
                    if (ConstUtil.ETH.equals(tokenItem.symbol)) {
                        transferEth(password, value);
                    } else {
                        transferEthErc20(password, value);
                    }
                } else {
                    SensorDataTrackUtils.transferAccount(tokenItem.symbol, value, receiveAddressEdit.getText().toString().trim(), walletItem.address, tokenItem.chainName, "2");
                    if (TextUtils.isEmpty(tokenItem.contractAddress)) {
                        transferAppChainToken(password, Double.valueOf(value));
                    } else {
                        transferAppChainErc20(password, Double.valueOf(value));
                    }
                }
            }
        });
        transferDialog.setConfirmData(walletItem.address, receiveAddressEdit.getText().toString(),
                value + tokenItem.symbol, feeValueText.getText().toString());
    }

    /**
     * transfer origin token of nervos
     *
     * @param value transfer value
     */
    private void transferAppChainToken(String password, double value) {
        AppChainRpcService.transferAppChain(receiveAddressEdit.getText().toString().trim(), value,
                "", tokenItem.chainId, password)
                .subscribe(new NeuronSubscriber<AppSendTransaction>() {
                    @Override
                    public void onError(Throwable e) {
                        transferAppChainError(e);
                    }

                    @Override
                    public void onNext(AppSendTransaction appSendTransaction) {
                        transferAppChainNormal(appSendTransaction);
                    }
                });
    }


    /**
     * transfer erc20 token of nervos
     *
     * @param value transfer value
     */
    private void transferAppChainErc20(String password, double value) {
        AppChainRpcService.setHttpProvider(SharePrefUtil.getChainHostFromId(tokenItem.chainId));
        AppChainRpcService.transferErc20(tokenItem, tokenItem.contractAddress,
                receiveAddressEdit.getText().toString().trim(), value, tokenItem.chainId, password)
                .subscribe(new NeuronSubscriber<AppSendTransaction>() {
                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        transferAppChainError(e);
                    }

                    @Override
                    public void onNext(AppSendTransaction appSendTransaction) {
                        transferAppChainNormal(appSendTransaction);
                    }
                });
    }

    private void transferAppChainNormal(AppSendTransaction appSendTransaction) {
        progressBar.setVisibility(View.GONE);
        if (!TextUtils.isEmpty(appSendTransaction.getSendTransactionResult().getHash())) {
            Toast.makeText(TransferActivity.this, R.string.transfer_success, Toast.LENGTH_SHORT).show();
            transferDialog.dismiss();
            finish();
        } else if (appSendTransaction.getError() != null &&
                !TextUtils.isEmpty(appSendTransaction.getError().getMessage())) {
            Toast.makeText(mActivity, appSendTransaction.getError().getMessage(),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mActivity, R.string.transfer_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void transferAppChainError(Throwable e) {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(TransferActivity.this,
                e.getMessage(), Toast.LENGTH_SHORT).show();
        transferDialog.dismiss();
    }


    /**
     * transfer origin token of ethereum
     *
     * @param value
     */
    private void transferEth(String password, String value) {
        EthRpcService.transferEth(receiveAddressEdit.getText().toString().trim(),
                Double.valueOf(value), mGasPrice, mGasLimit, "", password)
                .subscribe(new NeuronSubscriber<EthSendTransaction>() {
                    @Override
                    public void onError(Throwable e) {
                        transferAppChainError(e);
                    }

                    @Override
                    public void onNext(EthSendTransaction ethSendTransaction) {
                        transferEthereumNormal(ethSendTransaction);
                    }
                });
    }


    /**
     * transfer origin token of ethereum
     *
     * @param value
     */
    private void transferEthErc20(String password, String value) {
        EthRpcService.transferErc20(tokenItem, receiveAddressEdit.getText().toString().trim(),
                Double.valueOf(value), mGasPrice, mGasLimit, password)
                .subscribe(new NeuronSubscriber<EthSendTransaction>() {
                    @Override
                    public void onError(Throwable e) {
                        transferEthereumError(e);
                    }

                    @Override
                    public void onNext(EthSendTransaction ethSendTransaction) {
                        transferEthereumNormal(ethSendTransaction);
                    }
                });
    }


    private void transferEthereumNormal(EthSendTransaction ethSendTransaction) {
        progressBar.setVisibility(View.GONE);
        if (!TextUtils.isEmpty(ethSendTransaction.getTransactionHash())) {
            Toast.makeText(mActivity, R.string.transfer_success, Toast.LENGTH_SHORT).show();
            transferDialog.dismiss();
            finish();
        } else if (ethSendTransaction.getError() != null &&
                !TextUtils.isEmpty(ethSendTransaction.getError().getMessage())) {
            Toast.makeText(mActivity, ethSendTransaction.getError().getMessage(),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mActivity, R.string.transfer_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void transferEthereumError(Throwable e) {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(TransferActivity.this,
                e.getMessage(), Toast.LENGTH_SHORT).show();
        transferDialog.dismiss();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (transferDialog != null) {
            transferDialog.dismiss();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CODE_SCAN:
                if (null == data) return;
                Bundle bundle = data.getExtras();
                if (bundle == null) return;
                if (bundle.getInt(CodeUtils.RESULT_TYPE) == CodeUtils.RESULT_SUCCESS) {
                    switch (bundle.getInt(CodeUtils.STRING_TYPE)) {
                        case org.nervos.neuron.util.QRUtils.CodeUtils.STRING_ADDRESS:
                            String result = bundle.getString(CodeUtils.RESULT_STRING);
                            receiveAddressEdit.setText(result);
                            break;
                        default:
                            Toast.makeText(this, R.string.address_error,
                                    Toast.LENGTH_LONG).show();
                            break;
                    }
                } else if (bundle.getInt(CodeUtils.RESULT_TYPE) == CodeUtils.RESULT_FAILED) {
                    QrCodeActivity.track("1", false);
                    Toast.makeText(TransferActivity.this, R.string.qrcode_handle_fail,
                            Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }
}
