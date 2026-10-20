// SPDX-License-Identifier: GPL-3.0-or-later
#import "CVirtualDisplay.h"

@interface NSObject (ALXPVirtualDisplay)
// CGVirtualDisplayDescriptor
- (void)setQueue:(dispatch_queue_t)queue;
- (void)setName:(NSString *)name;
- (void)setMaxPixelsWide:(unsigned int)value;
- (void)setMaxPixelsHigh:(unsigned int)value;
- (void)setSizeInMillimeters:(CGSize)size;
- (void)setProductID:(unsigned int)value;
- (void)setVendorID:(unsigned int)value;
- (void)setSerialNum:(unsigned int)value;
// CGVirtualDisplayMode
- (instancetype)initWithWidth:(NSUInteger)width height:(NSUInteger)height refreshRate:(double)rate;
// CGVirtualDisplaySettings
- (void)setModes:(NSArray *)modes;
- (void)setHiDPI:(unsigned int)value;
// CGVirtualDisplay
- (instancetype)initWithDescriptor:(id)descriptor;
- (BOOL)applySettings:(id)settings;
- (CGDirectDisplayID)displayID;
@end

id ALXPVirtualDisplayCreate(NSString *name, unsigned int width, unsigned int height,
                            double widthMillimeters, double heightMillimeters) {
    Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    Class displayClass = NSClassFromString(@"CGVirtualDisplay");
    if (!descriptorClass || !modeClass || !settingsClass || !displayClass) return nil;

    id descriptor = [[descriptorClass alloc] init];
    [descriptor setQueue:dispatch_get_main_queue()];
    [descriptor setName:name];
    [descriptor setMaxPixelsWide:width];
    [descriptor setMaxPixelsHigh:height];
    [descriptor setSizeInMillimeters:CGSizeMake(widthMillimeters, heightMillimeters)];
    [descriptor setProductID:0x1234];
    [descriptor setVendorID:0x3456];
    [descriptor setSerialNum:0x0001];

    id display = [[displayClass alloc] initWithDescriptor:descriptor];
    if (!display) return nil;

    // HiDPI: the mode is in points, the backing store is width x height pixels.
    id mode = [[modeClass alloc] initWithWidth:width / 2 height:height / 2 refreshRate:60];
    id settings = [[settingsClass alloc] init];
    [settings setModes:@[mode]];
    [settings setHiDPI:1];
    if (![display applySettings:settings]) return nil;
    return display;
}

CGDirectDisplayID ALXPVirtualDisplayID(id display) {
    return [display displayID];
}
