const ethers = require('ethers');

async function checkTransaction() {
  const provider = new ethers.JsonRpcProvider('https://sepolia.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161');
  
  const txHash = '0x47cc820116d4271903886ab2569d194a9f6117e0980a9ffec18e956f987a221d';
  
  try {
    const tx = await provider.getTransaction(txHash);
    const receipt = await provider.getTransactionReceipt(txHash);
    
    console.log('Transaction:', tx);
    console.log('\nReceipt:', receipt);
    console.log('\nLogs:');
    receipt.logs.forEach((log, i) => {
      console.log(`Log ${i}:`, log);
    });
  } catch (error) {
    console.error('Error:', error);
  }
}

checkTransaction();
