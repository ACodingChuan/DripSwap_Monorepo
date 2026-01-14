#!/usr/bin/env python3
"""验证 Pair 地址计算"""

from eth_utils import keccak, to_checksum_address

# Factory 地址
FACTORY = '0x6c9258026a9272368e49bbb7d0a78c17bbe284bf'

# INIT_CODE_HASH  
INIT_CODE_HASH = '0x0d793e0bc737382e20c7a174911671209b6e833da3cb64b5c75e1940da1f1c21'

# 测试数据
test_pairs = [
    # Sepolia
    ('0xaea8c2f08b10fe1853300df4332e462b449e19d6', '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', 'sepolia', '0xdae14e909bb4b77a2c187721b877279967195893', 'vBTC-vUSDC'),
    ('0xaea8c2f08b10fe1853300df4332e462b449e19d6', '0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', 'sepolia', '0x3c5f6694456f9ce35cfe0b459c16efca380c70ea', 'vBTC-vUSDT'),
    ('0xaea8c2f08b10fe1853300df4332e462b449e19d6', '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', 'sepolia', '0xad00ab83a3aaa3fe48e21dd738e82b6aacd4ebbb', 'vBTC-vETH'),
    ('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', '0x0c156e2f45a812ad743760a88d73fb22879bc299', 'sepolia', '0x63ff7b7974e5b1b9b944ac0fa87f98dbe5a2fa1d', 'vDAI-vUSDT'),
    ('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', '0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454', 'sepolia', '0x2855b9febe9c16617ce5a4a66f50838fdb806ce7', 'vLINK-vUSDT'),
    ('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', 'sepolia', '0x5623e52a5f4cfd272028f129291b43bb42a29c6d', 'vUSDC-vUSDT'),
    ('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', 'sepolia', '0x9e1e7211fddff362fb3289eccd6e93b21284f980', 'vUSDT-vETH'),
    ('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', '0x0c156e2f45a812ad743760a88d73fb22879bc299', 'sepolia', '0x6bf8659fe87a250bcf40938021092726cbbe0ad9', 'vDAI-vETH'),
    ('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', '0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454', 'sepolia', '0x4047eddaa71f98700dc0f9eb4e21c2427ca4a427', 'vLINK-vETH'),
    ('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', 'sepolia', '0x25dccbf72a348de92bdf646bfaaaf66adc7225c7', 'vUSDC-vETH'),
    
    # Scroll Sepolia
    ('0x0c156e2f45a812ad743760a88d73fb22879bc299', '0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454', 'scroll-sepolia', '0x1a23c7a16a1a2153460585982837957b5fe637cc', 'vDAI-vLINK'),
    ('0x0c156e2f45a812ad743760a88d73fb22879bc299', '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', 'scroll-sepolia', '0x5208d3802d520ccd5dc4a00922c68c758d342807', 'vDAI-vUSDC'),
    ('0x0c156e2f45a812ad743760a88d73fb22879bc299', '0xaea8c2f08b10fe1853300df4332e462b449e19d6', 'scroll-sepolia', '0x62b17ec4d1f4b274bf998d6bcd4570a9f8e45fe9', 'vDAI-vBTC'),
]

def compute_create2_address(factory, salt, init_code_hash):
    """计算 CREATE2 地址"""
    # 0xff + factory + salt + init_code_hash
    data = bytes.fromhex('ff') + bytes.fromhex(factory[2:]) + bytes.fromhex(salt[2:]) + bytes.fromhex(init_code_hash[2:])
    hash_result = keccak(data)
    # 取后 20 字节作为地址
    address = '0x' + hash_result[-20:].hex()
    return to_checksum_address(address)

def main():
    print('=== Verifying Pair Address Calculation ===')
    print(f'Factory: {FACTORY}')
    print(f'INIT_CODE_HASH: {INIT_CODE_HASH}')
    print('')
    
    pass_count = 0
    fail_count = 0
    
    for token_a, token_b, network, expected, name in test_pairs:
        # 排序 token（小地址在前）
        token0, token1 = sorted([token_a.lower(), token_b.lower()])
        
        # 计算 salt（标准 Uniswap V2 方式）
        # salt = keccak256(abi.encodePacked(token0, token1))
        salt_data = bytes.fromhex(token0[2:]) + bytes.fromhex(token1[2:])
        salt = '0x' + keccak(salt_data).hex()
        
        # 计算 pair 地址（CREATE2）
        computed = compute_create2_address(FACTORY, salt, INIT_CODE_HASH)
        
        is_match = computed.lower() == expected.lower()
        
        if is_match:
            print(f'[PASS] {name} ({network})')
            print(f'  Expected: {expected}')
            print(f'  Computed: {computed}')
            pass_count += 1
        else:
            print(f'[FAIL] {name} ({network})')
            print(f'  Token0: {token0}')
            print(f'  Token1: {token1}')
            print(f'  Expected: {expected}')
            print(f'  Computed: {computed}')
            print(f'  Salt: {salt}')
            fail_count += 1
        print('')
    
    print('=== Summary ===')
    print(f'Total tests: {len(test_pairs)}')
    print(f'Passed: {pass_count}')
    print(f'Failed: {fail_count}')
    print('')
    
    if fail_count == 0:
        print('[OK] All pair addresses match!')
        print('子图中的 generatePoolAddress 函数逻辑正确。')
        print('')
        print('Salt 生成规则：keccak256(abi.encodePacked(token0, token1))')
        print('这是标准的 Uniswap V2 做法，token0 < token1 按地址排序。')
    else:
        print('[ERROR] Some pair addresses do not match!')
        print('请检查 salt 生成或 INIT_CODE_HASH。')

if __name__ == '__main__':
    main()
