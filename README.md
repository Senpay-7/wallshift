# Wallshift

An ad-free Android live wallpaper app that rotates my own photos and GIFs. It is the Kotlin / Jetpack Compose rewrite of [Wallpaper Shuffle](https://github.com/Senpay-7/wallpaper-shuffle). I built it for my own phone and use it every day.

> Personal project, built with AI coding assistants under my direction: I set the requirements, reviewed the result and tested it on my phone.

## Download
[`release/Wallshift-1.0-release.apk`](release/Wallshift-1.0-release.apk), version 1.0, about 1.5 MB. Install it on Android with "Install unknown apps" allowed for your file manager.

## What it does
- **Live wallpaper** built on Android's `WallpaperService`, with photos and animated GIFs.
- **Home-screen widget:** a tap area on the home screen for changing the wallpaper.
- **Automatic changes on a schedule:** WorkManager plus exact alarms, re-armed after a reboot or an app update.
- **Local only:** your library lives in a Room database on the device; the app has no internet permission, no ads, no accounts and no analytics.

## Stack
Kotlin, Jetpack Compose, Room, WorkManager, DataStore, AndroidX App Startup; minified release build (R8).

## Source code
This repository currently contains the release build. The source will be added after clean-up; the earlier Java / OpenGL ES version is fully open in [wallpaper-shuffle](https://github.com/Senpay-7/wallpaper-shuffle).
