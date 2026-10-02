package com.lagradost.cloudstream4.android

/**
 * Superclasses of the Android classes desktop generates stubs for. The JVM checks these when an
 * extension passes, say, a TextView where a View is expected, so the stubs need the real
 * hierarchy. Classes not listed extend Object, or Exception and Error going by their name.
 */
internal object StubHierarchy {
    /** Namespaces whose missing classes are generated, in internal name form */
    val stubbedPrefixes = listOf(
        "android/",
        "androidx/",
        "dalvik/",
        "com/android/",
        "com/google/android/",
        "org/xmlpull/",
        // App classes extensions use that desktop has no version of, such as the sync providers
        "com/lagradost/cloudstream3/",
    )

    fun isStubbed(internalName: String) = stubbedPrefixes.any(internalName::startsWith)

    fun superclassOf(internalName: String): String {
        superclasses[internalName]?.let { return it }
        val simple = internalName.substringAfterLast('/').substringAfterLast('$')
        return when {
            simple.endsWith("Exception") -> "java/lang/RuntimeException"
            simple.endsWith("Error") -> "java/lang/Error"
            else -> "java/lang/Object"
        }
    }

    private val superclasses: Map<String, String> = buildMap {
        fun extends(parent: String, vararg children: String) = children.forEach { put(it, parent) }

        // Views
        extends("android/view/View", "android/view/ViewGroup", "android/widget/TextView", "android/widget/ImageView",
            "android/widget/ProgressBar", "android/view/SurfaceView", "android/view/TextureView", "android/widget/Space")
        extends("android/view/ViewGroup", "android/widget/LinearLayout", "android/widget/FrameLayout",
            "android/widget/RelativeLayout", "android/widget/AdapterView", "android/widget/AbsoluteLayout",
            "android/widget/GridLayout", "androidx/recyclerview/widget/RecyclerView",
            "androidx/constraintlayout/widget/ConstraintLayout", "androidx/coordinatorlayout/widget/CoordinatorLayout",
            "androidx/viewpager/widget/ViewPager", "com/google/android/material/internal/FlowLayout")
        extends("android/widget/AbsoluteLayout", "android/webkit/WebView")
        extends("android/widget/FrameLayout", "android/widget/ScrollView", "android/widget/HorizontalScrollView",
            "androidx/cardview/widget/CardView", "androidx/core/widget/NestedScrollView",
            "androidx/fragment/app/FragmentContainerView", "androidx/swiperefreshlayout/widget/SwipeRefreshLayout")
        extends("android/widget/LinearLayout", "android/widget/RadioGroup", "android/widget/TableLayout",
            "android/widget/TableRow", "android/widget/SearchView", "androidx/appcompat/widget/LinearLayoutCompat",
            "com/google/android/material/textfield/TextInputLayout")
        extends("android/widget/AdapterView", "android/widget/AbsListView", "android/widget/AbsSpinner")
        extends("android/widget/AbsListView", "android/widget/ListView", "android/widget/GridView")
        extends("android/widget/AbsSpinner", "android/widget/Spinner")
        extends("android/widget/TextView", "android/widget/Button", "android/widget/EditText",
            "androidx/appcompat/widget/AppCompatTextView")
        extends("android/widget/Button", "android/widget/CompoundButton", "androidx/appcompat/widget/AppCompatButton")
        extends("android/widget/CompoundButton", "android/widget/CheckBox", "android/widget/Switch",
            "android/widget/RadioButton", "android/widget/ToggleButton", "androidx/appcompat/widget/SwitchCompat")
        extends("android/widget/EditText", "android/widget/AutoCompleteTextView", "androidx/appcompat/widget/AppCompatEditText")
        extends("android/widget/ImageView", "android/widget/ImageButton", "androidx/appcompat/widget/AppCompatImageView")
        extends("android/widget/ProgressBar", "android/widget/AbsSeekBar")
        extends("android/widget/AbsSeekBar", "android/widget/SeekBar", "android/widget/RatingBar")
        extends("androidx/appcompat/widget/AppCompatButton", "com/google/android/material/button/MaterialButton")
        extends("androidx/appcompat/widget/AppCompatEditText", "com/google/android/material/textfield/TextInputEditText")
        extends("android/widget/CheckBox", "androidx/appcompat/widget/AppCompatCheckBox")
        extends("androidx/appcompat/widget/AppCompatCheckBox", "com/google/android/material/chip/Chip")
        extends("com/google/android/material/internal/FlowLayout", "com/google/android/material/chip/ChipGroup")
        extends("androidx/appcompat/widget/SwitchCompat", "com/google/android/material/materialswitch/MaterialSwitch",
            "com/google/android/material/switchmaterial/SwitchMaterial")
        extends("androidx/cardview/widget/CardView", "com/google/android/material/card/MaterialCardView")

        // Layout parameters
        extends("android/view/ViewGroup\$LayoutParams", "android/view/ViewGroup\$MarginLayoutParams",
            "android/view/WindowManager\$LayoutParams")
        extends("android/view/ViewGroup\$MarginLayoutParams", "android/widget/LinearLayout\$LayoutParams",
            "android/widget/FrameLayout\$LayoutParams", "android/widget/RelativeLayout\$LayoutParams",
            "androidx/recyclerview/widget/RecyclerView\$LayoutParams",
            "androidx/constraintlayout/widget/ConstraintLayout\$LayoutParams")

        // Drawables and animation
        extends("android/graphics/drawable/Drawable", "android/graphics/drawable/GradientDrawable",
            "android/graphics/drawable/ColorDrawable", "android/graphics/drawable/BitmapDrawable",
            "android/graphics/drawable/ShapeDrawable", "android/graphics/drawable/LayerDrawable",
            "android/graphics/drawable/DrawableContainer", "android/graphics/drawable/InsetDrawable")
        extends("android/graphics/drawable/DrawableContainer", "android/graphics/drawable/StateListDrawable")
        extends("android/graphics/drawable/LayerDrawable", "android/graphics/drawable/RippleDrawable")
        extends("android/animation/Animator", "android/animation/ValueAnimator", "android/animation/AnimatorSet")
        extends("android/animation/ValueAnimator", "android/animation/ObjectAnimator")
        extends("android/text/style/CharacterStyle", "android/text/style/MetricAffectingSpan",
            "android/text/style/ForegroundColorSpan", "android/text/style/BackgroundColorSpan",
            "android/text/style/ClickableSpan", "android/text/style/UnderlineSpan", "android/text/style/StrikethroughSpan")
        extends("android/text/style/MetricAffectingSpan", "android/text/style/StyleSpan",
            "android/text/style/RelativeSizeSpan", "android/text/style/AbsoluteSizeSpan", "android/text/style/TypefaceSpan")

        // Dialogs, fragments and activities
        extends("android/app/Dialog", "android/app/AlertDialog", "androidx/appcompat/app/AppCompatDialog")
        extends("androidx/appcompat/app/AppCompatDialog", "androidx/appcompat/app/AlertDialog",
            "com/google/android/material/bottomsheet/BottomSheetDialog")
        extends("android/app/Fragment", "android/app/DialogFragment")
        extends("androidx/fragment/app/Fragment", "androidx/fragment/app/DialogFragment",
            "androidx/preference/PreferenceFragmentCompat")
        extends("androidx/fragment/app/DialogFragment", "androidx/appcompat/app/AppCompatDialogFragment")
        extends("androidx/appcompat/app/AppCompatDialogFragment", "com/google/android/material/bottomsheet/BottomSheetDialogFragment")
        extends("android/app/Activity", "androidx/core/app/ComponentActivity")
        extends("androidx/core/app/ComponentActivity", "androidx/activity/ComponentActivity")
        extends("androidx/activity/ComponentActivity", "androidx/fragment/app/FragmentActivity")
        extends("androidx/fragment/app/FragmentActivity", "androidx/appcompat/app/AppCompatActivity")
        extends("android/content/ContextWrapper", "android/app/Service", "android/view/ContextThemeWrapper")
        extends("android/view/ContextThemeWrapper", "androidx/appcompat/view/ContextThemeWrapper")

        // Preferences
        extends("androidx/preference/Preference", "androidx/preference/TwoStatePreference",
            "androidx/preference/DialogPreference", "androidx/preference/PreferenceGroup",
            "androidx/preference/SeekBarPreference")
        extends("androidx/preference/TwoStatePreference", "androidx/preference/SwitchPreferenceCompat",
            "androidx/preference/CheckBoxPreference", "androidx/preference/SwitchPreference")
        extends("androidx/preference/DialogPreference", "androidx/preference/EditTextPreference",
            "androidx/preference/ListPreference", "androidx/preference/MultiSelectListPreference")
        extends("androidx/preference/PreferenceGroup", "androidx/preference/PreferenceCategory",
            "androidx/preference/PreferenceScreen")

        // Lists
        extends("androidx/recyclerview/widget/RecyclerView\$LayoutManager", "androidx/recyclerview/widget/LinearLayoutManager")
        extends("androidx/recyclerview/widget/LinearLayoutManager", "androidx/recyclerview/widget/GridLayoutManager")
        extends("androidx/recyclerview/widget/RecyclerView\$Adapter", "androidx/recyclerview/widget/ListAdapter")
        extends("androidx/recyclerview/widget/RecyclerView\$ItemDecoration", "androidx/recyclerview/widget/ItemTouchHelper")
        extends("androidx/recyclerview/widget/ItemTouchHelper\$Callback", "androidx/recyclerview/widget/ItemTouchHelper\$SimpleCallback")
        extends("android/widget/BaseAdapter", "android/widget/ArrayAdapter")

        // Lifecycle
        extends("androidx/lifecycle/LiveData", "androidx/lifecycle/MutableLiveData")
        extends("androidx/lifecycle/MutableLiveData", "androidx/lifecycle/MediatorLiveData")
    }
}
