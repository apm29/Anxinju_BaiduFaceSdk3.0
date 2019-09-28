//package com.apm.anxinju.main.activity;
//
//import android.content.DialogInterface;
//import android.graphics.PointF;
//import android.os.Bundle;
//import android.os.Handler;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.view.ViewGroup;
//
//import androidx.annotation.NonNull;
//import androidx.annotation.Nullable;
//import androidx.fragment.app.DialogFragment;
//
//import com.apm.anxinju_baidufacesdk30.R;
//import com.apm.qrcode.QRScanView;
//
//
//public class QrDialog extends DialogFragment {
//    @Nullable
//    @Override
//    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
//        return inflater.inflate(R.layout.layout_qrcode_dialog,container,false);
//    }
//
//    @Override
//    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
//        super.onViewCreated(view, savedInstanceState);
//        QRScanView qrScanView = view.findViewById(R.id.layout_qrcode);
//        qrScanView.setIqr(new QRScanView.IQR() {
//            @Override
//            public void onQrText(String qrText, PointF[] points) {
//                qrInteractions.onQrText(qrText,points);
//            }
//        });
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                dismiss();
//            }
//        },30_000);
//    }
//
//
//    private Handler handler = new Handler();
//
//    @Override
//    public void onResume() {
//        super.onResume();
//        View view = getView();
//        if(view !=null) {
//            QRScanView qrScanView = view.findViewById(R.id.layout_qrcode);
//            qrScanView.onResume();
//        }
//    }
//
//
//    @Override
//    public void onPause() {
//        super.onPause();
//
//        View view = getView();
//        if(view !=null) {
//            QRScanView qrScanView = view.findViewById(R.id.layout_qrcode);
//            System.out.println("--------------qrScanView.onPause()1------------");
//            qrScanView.onPause();
//            System.out.println("--------------qrScanView.onPause()2------------");
//        }
//        System.out.println("--------------onQrDialogDismiss1------------");
//        //qrInteractions.onQrDialogPause();
//        System.out.println("--------------onQrDialogDismiss2------------");
//    }
//
//    @Override
//    public void onDismiss(@NonNull DialogInterface dialog) {
//        super.onDismiss(dialog);
//        qrInteractions.onQrDialogPause();
//    }
//
//    private QRInteractions qrInteractions;
//
//    public QrDialog(QRInteractions qrInteractions) {
//        this.qrInteractions = qrInteractions;
//    }
//
//    public interface QRInteractions{
//        void onQrText(String text, PointF[] points);
//        void onQrDialogPause();
//    }
//}
