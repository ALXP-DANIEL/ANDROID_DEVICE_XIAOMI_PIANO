/*
 * Copyright (C) 2014 The Android Open Source Project
 *               2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <hardware/hardware.h>
#include <hardware/hw_auth_token.h>

#define FINGERPRINT_MODULE_API_VERSION_1_0 HARDWARE_MODULE_API_VERSION(1, 0)
#define FINGERPRINT_MODULE_API_VERSION_2_0 HARDWARE_MODULE_API_VERSION(2, 0)
#define FINGERPRINT_MODULE_API_VERSION_2_1 HARDWARE_MODULE_API_VERSION(2, 1)
#define FINGERPRINT_MODULE_API_VERSION_3_0 HARDWARE_MODULE_API_VERSION(3, 0)
#define FINGERPRINT_HARDWARE_MODULE_ID "fingerprint"

#define NUM_FINGERS 5

typedef enum fingerprint_msg_type {
    FINGERPRINT_ERROR = -1,
    FINGERPRINT_ACQUIRED = 1,
    FINGERPRINT_TEMPLATE_ENROLLING = 3,
    FINGERPRINT_TEMPLATE_REMOVED = 4,
    FINGERPRINT_AUTHENTICATED = 5,
    FINGERPRINT_TEMPLATE_ENUMERATING = 6,
    FINGERPRINT_CHALLENGE_GENERATED = 7,
    FINGERPRINT_CHALLENGE_REVOKED = 8,
    FINGERPRINT_AUTHENTICATOR_ID_RETRIEVED = 9,
    FINGERPRINT_AUTHENTICATOR_ID_INVALIDATED = 10,
    FINGERPRINT_RESET_LOCKOUT = 11,
} fingerprint_msg_type_t;

typedef enum fingerprint_error {
    FINGERPRINT_ERROR_HW_UNAVAILABLE = 1,
    FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2,
    FINGERPRINT_ERROR_TIMEOUT = 3,
    FINGERPRINT_ERROR_NO_SPACE = 4,
    FINGERPRINT_ERROR_CANCELED = 5,
    FINGERPRINT_ERROR_UNABLE_TO_REMOVE = 6,
    FINGERPRINT_ERROR_LOCKOUT = 7,
    FINGERPRINT_ERROR_VENDOR_BASE = 1000
} fingerprint_error_t;

typedef enum fingerprint_acquired_info {
    FINGERPRINT_ACQUIRED_GOOD = 0,
    FINGERPRINT_ACQUIRED_PARTIAL = 1,
    FINGERPRINT_ACQUIRED_INSUFFICIENT = 2,
    FINGERPRINT_ACQUIRED_IMAGER_DIRTY = 3,
    FINGERPRINT_ACQUIRED_TOO_SLOW = 4,
    FINGERPRINT_ACQUIRED_TOO_FAST = 5,
    FINGERPRINT_ACQUIRED_DETECTED = 6,
    FINGERPRINT_ACQUIRED_VENDOR_BASE = 1000
} fingerprint_acquired_info_t;

typedef struct fingerprint_finger_id {
    uint32_t fid;
} fingerprint_finger_id_t;

typedef struct fingerprint_enroll {
    fingerprint_finger_id_t finger;
    uint32_t samples_remaining;
    uint64_t msg;
} fingerprint_enroll_t;

typedef struct fingerprint_iterator {
    fingerprint_finger_id_t fingers[NUM_FINGERS];
} fingerprint_iterator_t;

typedef fingerprint_iterator_t fingerprint_enumerated_t;
typedef fingerprint_iterator_t fingerprint_removed_t;

typedef struct fingerprint_acquired {
    fingerprint_acquired_info_t acquired_info;
} fingerprint_acquired_t;

typedef struct fingerprint_authenticated {
    fingerprint_finger_id_t finger;
    hw_auth_token_t hat;
} fingerprint_authenticated_t;

typedef struct fingerprint_authenticator {
    uint64_t id;
} fingerprint_authenticator_t;

typedef struct fingerprint_challenge {
    uint64_t value;
} fingerprint_challenge_t;

typedef struct fingerprint_msg {
    fingerprint_msg_type_t type;
    union {
        fingerprint_error_t error;
        fingerprint_enroll_t enroll;
        fingerprint_enumerated_t enumerated;
        fingerprint_removed_t removed;
        fingerprint_acquired_t acquired;
        fingerprint_authenticated_t authenticated;
        fingerprint_authenticator_t authenticator;
        fingerprint_challenge_t challenge;
    } data;
} fingerprint_msg_t;

typedef void (*fingerprint_notify_t)(const fingerprint_msg_t* msg);

typedef struct fingerprint_device {
    struct hw_device_t common;

    fingerprint_notify_t notify;

    int (*set_notify)(struct fingerprint_device* dev, fingerprint_notify_t notify);
    uint64_t (*generate_challenge)(struct fingerprint_device* dev);
    int (*revoke_challenge)(struct fingerprint_device* dev, uint64_t challenge);
    int (*enroll)(struct fingerprint_device* dev, const hw_auth_token_t* hat);
    uint64_t (*get_authenticator_id)(struct fingerprint_device* dev);
    uint64_t (*invalidate_authenticator_id)(struct fingerprint_device* dev);
    int (*cancel)(struct fingerprint_device* dev);
    int (*enumerate)(struct fingerprint_device* dev);
    int (*remove)(struct fingerprint_device* dev, uint32_t* fids, uint32_t count);
    int (*set_active_group)(struct fingerprint_device* dev, uint32_t userid,
                            const char* store_path);
    int (*authenticate)(struct fingerprint_device* dev, uint64_t operation_id);
    int (*reset_lockout)(struct fingerprint_device* dev, const hw_auth_token_t* hat);
    void (*onPointerDown)(struct fingerprint_device* dev, int32_t pointerId, int32_t x, int32_t y,
                          float minor, float major);
    void (*onPointerUp)(struct fingerprint_device* dev, int32_t pointerId);
    int (*extCmd)(struct fingerprint_device* dev, int32_t cmd, int32_t param);

    void* reserved[4];
} fingerprint_device_t;

typedef struct fingerprint_module {
    struct hw_module_t common;
} fingerprint_module_t;
