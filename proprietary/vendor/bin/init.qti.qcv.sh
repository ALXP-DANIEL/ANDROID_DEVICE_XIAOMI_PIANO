#! /vendor/bin/sh
#=============================================================================
# Copyright (c) 2020, 2021 Qualcomm Technologies, Inc.
# All Rights Reserved.
# Confidential and Proprietary - Qualcomm Technologies, Inc.
#=============================================================================

soc_id=`cat /sys/devices/soc0/soc_id` 2> /dev/null
chip_id=`cat /sys/devices/soc0/chip_id` 2> /dev/null

# Store soc_id in ro.vendor.qti.soc_id
setprop ro.vendor.qti.soc_id $soc_id

# For chipsets in QCV family, convert soc_id to soc_name
# and store it in ro.vendor.qti.soc_name.
if [ "$soc_id" -eq 618 ] || [ "$soc_id" -eq 639 ]; then
    setprop ro.vendor.qti.soc_name sun
    # Store chip_id in ro.vendor.qti.soc_model
    setprop ro.vendor.qti.soc_model $chip_id
    # MIUI DEL: CUST_PreloadCustomProp
    #setprop ro.vendor.media_performance_class 35
    # END CUST_PreloadCustomProp
elif [ "$soc_id" -eq 655 ] || [ "$soc_id" -eq 681 ] || [ "$soc_id" -eq 694 ]; then
    setprop ro.vendor.qti.soc_name tuna
    setprop ro.vendor.qti.soc_model $chip_id
    # setprop ro.vendor.media_performance_class 35
elif [ "$soc_id" -eq 659 ] || [ "$soc_id" -eq 686 ]; then
    setprop ro.vendor.qti.soc_name kera
    setprop ro.vendor.qti.soc_model $chip_id
elif [ "$soc_id" -eq 507 ] || [ "$soc_id" -eq 565 ]; then
    setprop ro.vendor.qti.soc_name blair
elif [ "$soc_id" -eq 578 ]; then
    setprop ro.vendor.qti.soc_name blair
elif [ "$soc_id" -eq 454 ]; then
    setprop ro.vendor.qti.soc_name holi
elif [ "$soc_id" -eq 472 ]; then
    setprop ro.vendor.qti.soc_name holi
elif [ "$soc_id" -eq 557 ] || [ "$soc_id" -eq 577 ]; then
    setprop ro.vendor.qti.soc_name pineapple
    # Store chip_id in ro.vendor.qti.soc_model
    setprop ro.vendor.qti.soc_model $chip_id
    setprop ro.vendor.media_performance_class 34
elif [ "$soc_id" -eq 537 ] || [ "$soc_id" -eq 583 ]; then
    setprop ro.vendor.qti.soc_name parrot
    setprop ro.vendor.qti.soc_model SM6450
elif [ "$soc_id" -eq 663 ]; then
    setprop ro.vendor.qti.soc_name parrot
    setprop ro.vendor.qti.soc_model SM6475
elif [ "$soc_id" -eq 631 ]; then
    setprop ro.vendor.qti.soc_name parrot
    setprop ro.vendor.qti.soc_model SM6450Q
elif [ "$soc_id" -eq 613 ] || [ "$soc_id" -eq 638 ]; then
    setprop ro.vendor.qti.soc_name parrot
    setprop ro.vendor.qti.soc_model SM7435
elif [ "$soc_id" -eq 633 ] || [ "$soc_id" -eq 634 ]; then
    setprop ro.vendor.qti.soc_name parrot
    setprop ro.vendor.qti.soc_model SG6150
elif [ "$soc_id" -eq 568 ] || [ "$soc_id" -eq 602 ] || [ "$soc_id" -eq 653 ] || [ "$soc_id" -eq 654 ]; then
    setprop ro.vendor.qti.soc_name ravelin
    setprop ro.vendor.qti.soc_model SM4450
fi
