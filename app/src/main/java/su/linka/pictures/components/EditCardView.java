//package su.linka.pictures.components;
//
//import android.app.Activity;
//import android.content.Context;
//import android.content.Intent;
//import android.graphics.Bitmap;
//import android.graphics.ImageDecoder;
//import android.graphics.drawable.Drawable;
//import android.graphics.drawable.PictureDrawable;
//import android.media.MediaPlayer;
//import android.net.Uri;
//import android.os.Build;
//import android.provider.MediaStore;
//import android.util.Log;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.widget.Button;
//import android.widget.EditText;
//import android.widget.ImageView;
//import android.widget.LinearLayout;
//import android.widget.RadioGroup;
//import android.widget.Toast;
//
//import androidx.core.content.res.ResourcesCompat;
//
//
//import java.io.File;
//import java.io.IOException;
//
//import su.linka.pictures.ActivityResultListener;
//import su.linka.pictures.Callback;
//import su.linka.pictures.Card;
//import su.linka.pictures.R;
//import su.linka.pictures.Set;
//import su.linka.pictures.TTS;
//import su.linka.pictures.Utils;
//import su.linka.pictures.activity.SetEditActivity;
//
//public class EditCardView extends LinearLayout {
//    private static final int PICK_IMAGE = 0;
//    private Card card;
//    private final Set set;
//    private final TTS tts;
//    private MediaPlayer mp;
//    private RadioGroup cardTypeRadio;
//    private View imageEditBlock;
//    private ImageView imageView;
//    private View imageEditButtons;
//    private EditText cardTitleEditText;
//    private Button playAudioButton;
//    private RecordButton recordButton;
//    private File currentAudioFile = null;
//    private Bitmap currentBitmap;
//
//    public EditCardView(Context context) {
//        super(context);
//        tts = new TTS(getContext());
//        mp = new MediaPlayer();
//        inflate(context);
//    }
//    public void  setCard(Card card) {
//        Card mCard = card;
//        if (card == null) {
//            mCard = new Card(0, 0);
//        }
//
//        this.card = mCard;
//    }
//    public void setSet(Set set) {
//
//    }
//        EditCardView mThis = this;
//        ((SetEditActivity) context).setOnActivityResultListener(new ActivityResultListener() {
//            @Override
//            public void onActivityResult(int requestCode, int resultCode, Intent data) {
//                mThis.onActivityResult(requestCode, resultCode, data);
//            }
//        });
//    }
//
//
//    private void inflate(Context context) {
//        LayoutInflater layoutInflater = (LayoutInflater) context
//                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
//        View view = layoutInflater.inflate(R.layout.edit_card_dialog, this, true);
//
//
//        cardTypeRadio = view.findViewById(R.id.card_type_radiogroup);
//        cardTitleEditText = view.findViewById(R.id.card_title_edittext);
//        imageEditBlock = view.findViewById(R.id.image_edit_block);
//        imageView = view.findViewById(R.id.image);
//        imageEditButtons = view.findViewById(R.id.image_edit_buttons);
//        playAudioButton = view.findViewById(R.id.play_audio_button);
//
//        recordButton = view.findViewById(R.id.record_audio_button);
//
//
//        cardTypeRadio.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
//            @Override
//            public void onCheckedChanged(RadioGroup group, int checkedId) {
//                changeCardType(checkedId - 1);
//            }
//        });
//        cardTypeRadio.check(card.cardType + 1);
//
//        if (card.title != null) {
//            cardTitleEditText.setText(card.title);
//        }
//
//        loadImage();
//        changeCardType((card.cardType == 3 ? 0 : card.cardType));
//
//        view.findViewById(R.id.choose_image_button).setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                chooseImage();
//            }
//        });
//        view.findViewById(R.id.generate_image_button).setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                generateImage();
//            }
//        });
//        view.findViewById(R.id.generate_audio_button).setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                generateAudio();
//            }
//        });
//        playAudioButton.setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                try {
//                    playAudio();
//                } catch (IOException e) {
//                    Log.e(getClass().getCanonicalName(), "onClick: ", e );
//                    Toast.makeText(getContext(), R.string.play_audio_error, Toast.LENGTH_LONG).show();
//                }
//            }
//        });
//        recordButton.setOnRecordListener(new Callback<File>() {
//            @Override
//            public void onDone(File result) {
//                currentAudioFile = result;
//                playAudioButton.setEnabled(true);
//            }
//
//            @Override
//            public void onFail(Exception error) {
//
//            }
//        });
//    }
//
//    private void playAudio() throws IOException {
//        if(currentAudioFile == null) return;
//           mp.reset();
//
//        mp.setDataSource(currentAudioFile.getAbsolutePath());
//        mp.prepare();
//        mp.start();
//    }
//
//    private void generateAudio() {
//        InputDialog
//                .showDialog(getContext(), R.string.generate_audio, new Callback<String>() {
//                    @Override
//                    public void onDone(String result) {
//                        try {
//                            currentAudioFile = tts.speakToBuffer(result);
//                        } catch (Exception e) {
//                            Toast.makeText(getContext(), R.string.generate_audio_error, Toast.LENGTH_LONG).show();
//                            return;
//                        }
//
//                        playAudioButton.setEnabled(true);
//
//                             }
//
//                    @Override
//                    public void onFail(Exception error) {
//
//                    }
//                });
//    }
//
//    private void generateImage() {
//        InputDialog
//                .showDialog(getContext(), R.string.generate_image, new Callback<String>() {
//                    @Override
//                    public void onDone(String result) {
//                        currentBitmap = Utils.textAsBitmap(result);
//                        imageView.setImageBitmap(currentBitmap);
//                    }
//
//                    @Override
//                    public void onFail(Exception error) {
//
//                    }
//                });
//    }
//
//    private void chooseImage() {
//        Intent intent = new Intent();
//        intent.setType("image/*");
//        intent.setAction(Intent.ACTION_GET_CONTENT);
//        ((Activity) getContext()).startActivityForResult(Intent.createChooser(intent, getContext().getString(R.string.choose_image)), PICK_IMAGE);
//
//    }
//
//
//    private void onActivityResult(int requestCode, int resultCode, Intent data) {
//        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
//
//            Uri selectedImage = data.getData();
//            Bitmap bitmap = null;
//            try {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContext().getContentResolver(), selectedImage));
//                } else {
//                    bitmap = MediaStore.Images.Media.getBitmap(getContext().getContentResolver(), selectedImage);
//                }
//            } catch (IOException e) {
//                Toast.makeText(getContext(), R.string.image_open_error, Toast.LENGTH_LONG).show();
//                e.printStackTrace();
//                return;
//            }
//            currentBitmap = bitmap;
//            imageView.setImageBitmap(bitmap);
//        }
//    }
//
//
//    private void loadImage() {
//        if (card != null && card.imagePath != null) {
//            imageView.setImageBitmap(set.getBitmap(card.imagePath));
//        }
//    }
//
//    private void changeCardType(int checkedId) {
//        card.cardType = checkedId;
//        if (checkedId == 0) {
//            imageEditBlock.setVisibility(VISIBLE);
//            imageEditButtons.setVisibility(VISIBLE);
//            cardTitleEditText.setVisibility(VISIBLE);
//            loadImage();
//        } else if (checkedId == 1) {
//            imageEditBlock.setVisibility(VISIBLE);
//            imageEditButtons.setVisibility(GONE);
//            cardTitleEditText.setVisibility(GONE);
//            imageView.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.ic_baseline_space_bar_24, null));
//        } else if (checkedId == 2) {
//            imageEditBlock.setVisibility(GONE);
//            cardTitleEditText.setVisibility(GONE);
//        }
//    }
//    public void onDestroy(){
//        recordButton.onDestroy();
//    }
//
    //    public boolean validate() {
    //        switch (card.cardType){
    //            case 0:
    //                String text = cardTitleEditText.getText().toString();
    //                if(text.length()==0) return false;
    //                Drawable drawable = imageView.getDrawable();
    //                if(drawable==null) return false;
    //                if(currentAudioFile==null) return false;
    //                return true;
    //            case 1:
    //            case 2:
    //                return true;
    //            default:
    //                return false;
    //        }
    //    }
    //
//    public Card saveCard() {
//    if (card.cardType!=0) return card;
//        try {
//
//            card.audioPath = set.copyAudioFile(currentAudioFile).getName();
//
//           card.imagePath = set.saveBitmap(currentBitmap).getName();
//           card.title = cardTitleEditText.getText().toString();
//
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        return card;
//    }
//}
