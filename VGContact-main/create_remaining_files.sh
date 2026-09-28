#!/bin/bash

# Create remaining layouts
mkdir -p app/src/main/res/layout app/src/main/res/values app/src/main/res/drawable-v24

# Placeholder layouts (minimal - enough to compile)
for activity in home repost downloads community profile; do
    cat > "app/src/main/res/layout/activity_${activity}.xml" << LAYOUT
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:background="@color/vg_green">
            <TextView
                android:id="@+id/header_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/white"
                android:textSize="18sp"
                android:textStyle="bold"
                android:gravity="center_vertical"
                android:layout_gravity="center_vertical"
                android:paddingStart="16dp" />
        </FrameLayout>
        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="16dp">
                <LinearLayout
                    android:id="@+id/content_container"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical" />
            </LinearLayout>
        </ScrollView>
        <Button
            android:id="@+id/chat_btn"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_gravity="bottom|end"
            android:layout_margin="16dp"
            android:background="@color/vg_green"
            android:text="💬"
            android:textSize="24sp" />
        <com.google.android.material.bottomnavigation.BottomNavigationView
            android:id="@+id/bottom_nav"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/vg_dark"
            app:menu="@menu/bottom_nav_menu"
            xmlns:app="http://schemas.android.com/apk/res-auto" />
    </LinearLayout>
</FrameLayout>
LAYOUT
done

# Create menu
mkdir -p app/src/main/res/menu
cat > "app/src/main/res/menu/bottom_nav_menu.xml" << 'MENU'
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/nav_home" android:title="@string/nav_home" />
    <item android:id="@+id/nav_repost" android:title="@string/nav_repost" />
    <item android:id="@+id/nav_downloads" android:title="@string/nav_downloads" />
    <item android:id="@+id/nav_community" android:title="@string/nav_community" />
    <item android:id="@+id/nav_profile" android:title="@string/nav_profile" />
</menu>
MENU

# Create item layouts
cat > app/src/main/res/layout/item_file.xml << 'ITEM'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="12dp"
    android:layout_marginBottom="8dp"
    android:background="@color/white">
    <TextView android:id="@+id/file_name" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textStyle="bold" />
    <TextView android:id="@+id/file_count" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textSize="12sp" />
    <TextView android:id="@+id/file_status" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textSize="12sp" />
    <Button android:id="@+id/download_btn" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Download" android:layout_marginTop="8dp" />
</LinearLayout>
ITEM

# Create proguard rules
cat > app/proguard-rules.pro << 'PROGUARD'
-keep class com.vgcontact.app.** { *; }
-keep class org.json.** { *; }
-keepattributes SourceFile,LineNumberTable
PROGUARD

# Create debug keystore
keytool -genkey -v -keystore app/debug.keystore \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias vgkontactdebugkey \
    -storepass vgkontactdebug \
    -keypass vgkontactdebug \
    -dname "CN=VGContact,O=VGContact,C=US" 2>/dev/null || echo "Keystore already exists"

# Create gradle.properties
cat > gradle.properties << 'GRADLE_PROPS'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.enableJetifier=true
GRADLE_PROPS

echo "✅ All remaining files created"

